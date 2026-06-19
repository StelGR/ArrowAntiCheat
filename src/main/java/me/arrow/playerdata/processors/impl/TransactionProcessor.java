package me.arrow.playerdata.processors.impl;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowConfirmation;
import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.managers.logs.PlayerLog;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.OtherUtility;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

// a transaction processor that has brain damage slightly, only slightly though, please fix it for bedrock players, as without this all velocity calculation
// becomes impossible on bedrock, as we use transactions to verify velocity, no you can't cancel them for ever cus it will kick you
// and cancelling them will just flag speed either way cus it is dependant on transactions


public class TransactionProcessor implements Runnable {

    private final Profile profile;

    @Getter
    @Setter
    private int timeTransactionStart = 32000;

    @Getter
    @Setter
    private short sTimeTransactionStart = (short) 32000;

    private TaskUtils.CancellableTask bukkitTaskTransaction;


    // i can't seem to find correct number
    private static final int MAX_ATTEMPTS = 500;

    private int nextIntId = 32000;
    private short nextShortId = (short) 32000;

    private int currentIntId = -1;
    private short currentShortId = -1;

    private boolean waitingForTransaction;
    private boolean currentModernTransaction;

    private int attempts;
    private long currentSentTime;
    private boolean kickedForTransaction;

    public TransactionProcessor(Profile profile) {
        this.profile = profile;
        startTransaction();
    }

    public void startTransaction() {
        if (this.bukkitTaskTransaction == null) {
            this.bukkitTaskTransaction = TaskUtils.playerTimer(profile.getPlayer(), 1L, 1L, this);
        }
    }

    public void stopTransaction() {
        if (this.bukkitTaskTransaction != null) {
            this.bukkitTaskTransaction.cancel();
            this.bukkitTaskTransaction = null;
        }
    }

    @Override
    public void run() {
        if (profile == null) return;
        if (profile.getTick() < 60) return;
        if (kickedForTransaction) return;
        if (profile.getPlayer() == null || !profile.getPlayer().isOnline()) {
            stopTransaction();
            return;
        }

        processTransactions();
    }

    public void processTransactions() {
        try {
            boolean useModernTransaction = shouldUseModernTransaction();

            if (!waitingForTransaction) {
                createNextTransaction(useModernTransaction);
                sendCurrentTransaction();
                return;
            }

            if (wasCurrentTransactionAccepted()) {
                clearCurrentTransaction();
                createNextTransaction(useModernTransaction);
                sendCurrentTransaction();
                return;
            }

            attempts++;

            if (attempts >= MAX_ATTEMPTS) {
                kickForTransactionTimeout();
                return;
            }

            sendCurrentTransaction();
        } catch (Exception e) {
            OtherUtility.log("TransactionProcessor error: " + e.getMessage());
        }
    }

    private boolean shouldUseModernTransaction() {
        return PacketEvents.getAPI()
                .getServerManager()
                .getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_17);
    }

    private void createNextTransaction(boolean modern) {
        this.currentModernTransaction = modern;
        this.waitingForTransaction = true;
        this.attempts = 0;
        this.currentSentTime = System.currentTimeMillis();
        this.profile.setHasReceivedTransaction(false);

        if (modern) {
            int id = getNextTimeTransaction();
            this.currentIntId = id;
            this.currentShortId = -1;

            profile.getISentTransactions().clear();
            profile.getISentTransactions().put(id, currentSentTime);
        } else {
            short id = getNextSTimeTransaction();
            this.currentShortId = id;
            this.currentIntId = -1;

            profile.getSSentTransactions().clear();
            profile.getSSentTransactions().put(id, currentSentTime);
        }
    }

    private void sendCurrentTransaction() {
        if (!waitingForTransaction) return;

        if (currentModernTransaction) {
            WrapperPlayServerPing packet = new WrapperPlayServerPing(currentIntId);
            profile.sendPacket(packet);
        } else {
            WrapperPlayServerWindowConfirmation packet =
                    new WrapperPlayServerWindowConfirmation(0, currentShortId, false);
            profile.sendPacket(packet);
        }
    }

    private boolean wasCurrentTransactionAccepted() {
        if (!waitingForTransaction) return false;

        if (currentModernTransaction) {
            return !profile.getISentTransactions().containsKey(currentIntId);
        }

        return !profile.getSSentTransactions().containsKey(currentShortId);
    }

    private void clearCurrentTransaction() {
        if (currentModernTransaction) {
            profile.getISentTransactions().remove(currentIntId);
        } else {
            profile.getSSentTransactions().remove(currentShortId);
        }

        this.waitingForTransaction = false;
        this.currentIntId = -1;
        this.currentShortId = -1;
        this.currentSentTime = 0L;
        this.attempts = 0;
    }

    public int getNextTimeTransaction() {
        nextIntId--;

        if (nextIntId < 1) {
            nextIntId = timeTransactionStart;
        }

        return nextIntId;
    }

    public short getNextSTimeTransaction() {
        nextShortId--;

        if (nextShortId < 1) {
            nextShortId = sTimeTransactionStart;
        }

        return nextShortId;
    }

    private void kickForTransactionTimeout() {
        if (kickedForTransaction) return;

        kickedForTransaction = true;

        try {
            profile.getISentTransactions().clear();
            profile.getSSentTransactions().clear();

            if (profile.getPlayer() != null && profile.getPlayer().isOnline() && !profile.isBedrockPlayer() && Config.Setting.TRANSACTION_KICKS.getBoolean()) {
                String reason = "Timed out while processing transactions.";

                profile.kick(reason);

                Arrow.getInstance().getLogManager().addLogToQueue(new PlayerLog(
                        profile.getPlayer().getName(),
                        profile.getUUID().toString(),
                        "Transaction Processor",
                        sanitizeForLog("&c" + reason + " Attempts: " + attempts
                                + ", modern=" + currentModernTransaction
                                + ", bedrock=" + profile.isBedrockPlayer()
                                + ", id=" + (currentModernTransaction ? currentIntId : currentShortId))
                ));

                for (Player staff : Bukkit.getOnlinePlayers()) {

                    final Profile staffProfile = Arrow.getInstance().getProfileManager().getProfile(staff);

                    if (staffProfile == null || !staffProfile.isAlerts()) {
                        continue;
                    }

                    if (!staff.hasPermission(Permissions.ALERTS.getPermission())) {
                        continue;
                    }

                    staff.sendMessage(OtherUtility.translate("&8[&6Arrow&8] &c" + profile.getPlayer().getDisplayName() + " was kicked due to transaction timeout."));
                }
            }
        } catch (Exception e) {
            OtherUtility.log("Failed to kick/log transaction timeout: " + e.getMessage());
        } finally {
            stopTransaction();
        }
    }

    private String sanitizeForLog(String input) {
        if (input == null) return "";

        String out = org.bukkit.ChatColor.stripColor(input);
        out = out.replace("\r", "").trim();

        final int MAX = 1500;
        if (out.length() > MAX) {
            out = out.substring(0, MAX);
        }

        return out;
    }
}