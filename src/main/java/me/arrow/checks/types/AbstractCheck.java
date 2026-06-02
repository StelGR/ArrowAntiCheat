package me.arrow.checks.types;

import lombok.Getter;
import lombok.Setter;
import me.arrow.Arrow;
import me.arrow.api.events.AnticheatViolationEvent;
import me.arrow.api.events.VerboseEvent;
import me.arrow.checks.annotations.Development;
import me.arrow.checks.annotations.Experimental;
import me.arrow.checks.enums.CheckCategory;
import me.arrow.checks.enums.CheckMode;
import me.arrow.checks.enums.CheckType;
import me.arrow.enums.Permissions;
import me.arrow.files.Config;
import me.arrow.files.commentedfiles.CommentedFileConfiguration;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.MiscUtils;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.customutils.OtherUtility;
import me.arrow.utils.customutils.animationSystem.Animation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// this is the main check interface, where you can get the check info from, bypass permissions do not exist as i don't support servers that give bypass  to Youtubers

public abstract class AbstractCheck {

    protected final Profile profile;

    @Getter
    private final boolean enabled, canPunish;



    @Getter
    @Setter
    private String checkName, checkType, fullCheckName, description, checkMode, punishMode;
    private final boolean experimental;
    private final CheckCategory checkCategory;

    private final boolean development;
    @Setter
    @Getter
    private int vl = 1;
    @Getter
    private int maxVl;
    @Setter
    double buffer;
    @Getter
    private String verboseTitle, verboseInfo; //TODO: USE STRINGBUILDER

    public AbstractCheck(Profile profile, CheckType check, String type, String description) {

        this.profile = profile;
        this.checkName = check.getCheckName();
        this.checkType = type;
        this.description = description;



        final CommentedFileConfiguration config = Arrow.getInstance().getChecks();
        final String checkName = this.checkName;

        this.enabled = type.isEmpty()
                ? config.getBoolean(checkName + checkType + ".enabled")
                : config.getBoolean(checkName + checkType + ".enabled", config.getBoolean(checkName + checkType));

        this.maxVl = config.getInt(checkName + checkType + ".punish.vl");
        this.checkMode = config.getString(checkName + checkType + ".mode");
        this.canPunish = config.getBoolean(checkName + checkType + ".punish.enabled");
        this.punishMode = config.getString(checkName + checkType + ".punish.mode");

        if (checkMode == null) this.checkMode = CheckMode.FLAG.getCheckMode();
        if (punishMode == null) this.punishMode = "KICK";

        Class<? extends AbstractCheck> clazz = this.getClass();

        this.experimental = clazz.isAnnotationPresent(Experimental.class);

        this.development = clazz.isAnnotationPresent(Development.class);

        this.checkCategory = check.getCheckCategory();

        this.fullCheckName = this.checkName + (type.isEmpty() ? "" : (" (" + type + ")"));
    }


    protected void debug(Object info) {
        Bukkit.broadcastMessage(String.valueOf(info));
    }

    public void fail(String verboseTitle, String verboseInfo) {

        this.verboseTitle = verboseTitle;
        this.verboseInfo = verboseInfo;

        fail();
    }

    public void fail() {

        if (!enabled) return;

        //Development
        if (this.development) return;
        if (profile.isBanned()) return;
        //if (profile.isBypassing()) return;
        if (profile.isBedrockPlayer() && Config.Setting.IGNORE_BEDROCK.getBoolean()) return;

      //  if (profile.getPlayer().hasPermission(Permissions.BYPASS.getPermission())) return;


        if (checkMode != null) {
            if (checkMode.equals(CheckMode.MITIGATE.getCheckMode())) {
                profile.getMovementData().getSetbackProcessor().causeSetBack(getFullCheckName());
                return;
            }
            else if (checkMode.equals(CheckMode.BOTH.getCheckMode())) {
                profile.getMovementData().getSetbackProcessor().causeSetBack(getFullCheckName());
            }
        }

        //Just to make sure
        if (this.vl < 0) this.vl = 1;

        final Player p = profile.getPlayer();

        if (p == null) return;

        AnticheatViolationEvent violationEvent = new AnticheatViolationEvent(
                p,
                this.checkName,
                this.description,
                getCategory(),
                this.checkType,
                verboseTitle,
                verboseInfo,
                this.vl++,
                this.maxVl,
                this.experimental);

        Bukkit.getPluginManager().callEvent(violationEvent);

        if (violationEvent.isCancelled()) {

            this.vl--;

            return;
        }

        if (Config.Setting.TEST_SERVER_MODE_ENABLED.getBoolean()) {
            this.maxVl = 50;

            if (this.vl > this.maxVl) {
                profile.kick("Detected L");
                TaskUtils.task(() -> profile.getPlayer().getWorld().strikeLightningEffect(profile.getPlayer().getLocation()));
                Bukkit.broadcastMessage(OtherUtility.getPunishMessage(p));
                if (Config.Setting.WEBHOOK_ENABLED.getBoolean()) sendPunishWebhook(profile.getPlayer().getName(), "KICK");

                this.vl = 1;
                this.buffer = 0;

                return;
            }
            return;
        }



        if (this.vl > this.maxVl
                && Config.Setting.PUNISH_ENABLED.getBoolean()
                && isCanPunish()
                && getPunishMode().equals("BAN")
                && !profile.isBanned()
        ) {

            final String playerName = p.getName();

            String animationName = Config.Setting.BAN_ANIMATION_CURRENT.getString();

            Animation.Type animationType;

            try {
                animationType = Animation.Type.valueOf(animationName.toUpperCase());
            } catch (Exception ignored) {
                animationType = Animation.Type.DESTROYED;
            }

            profile.setBanned(true);
            boolean started = Arrow.getInstance().getAnimationManager().play(animationType, p, () -> {


                OtherUtility.antiCheatban(p);

                TaskUtils.task(() -> MiscUtils.consoleCommand(
                        Config.Setting.PUNISH_COMMAND.getString().replace("%player%", playerName)
                ));

                Bukkit.broadcastMessage(OtherUtility.getPunishMessage(p));

                if (Config.Setting.WEBHOOK_ENABLED.getBoolean()) {
                    sendPunishWebhook(playerName, "BAN");
                }

                this.vl = 1;
                this.buffer = 0;
            });

            if (!started) {
                return;
            }
        }
        else if (this.vl > this.maxVl
                && Config.Setting.PUNISH_ENABLED.getBoolean()
                && isCanPunish()
                && getPunishMode().equals("KICK")
                && !profile.isBanned()
        ) {
            profile.setBanned(true);
            profile.kick("Timed Out (A. K.)");
            //Bukkit.broadcastMessage(OtherUtility.getPunishMessage(p));
            if (Config.Setting.WEBHOOK_ENABLED.getBoolean()) sendPunishWebhook(profile.getPlayer().getName(), "KICK");

            this.vl = 1;
            this.buffer = 0;
        }
    }

    public void verbose(String verbosingClass, double bufferCurrent, double maxBuffer, String data) {
        final Player p = profile.getPlayer();

        if (p == null) return;

        if (profile.getVerbosingClass().equals(verbosingClass) || profile.getVerbosingClass().equalsIgnoreCase("All")) {
            if (profile.isVerbose()) {

                VerboseEvent violationEvent = new VerboseEvent(
                        p,
                        this.checkName,
                        this.checkType,
                        data,
                        bufferCurrent,
                        maxBuffer);

                Bukkit.getPluginManager().callEvent(violationEvent);
            }
        }
    }

    public CheckCategory getCategory() {
        return checkCategory;
    }

    public void resetVl() {
        this.vl = 1;
    }

    protected double increaseBuffer() {
        return this.buffer++;
    }

    protected double increaseBufferBy(double amount) {
        return this.buffer += amount;
    }

    protected void decreaseBuffer() {
        if (this.buffer != 0) {
            this.buffer = Math.max(0, this.buffer - 1);
        }
    }

    protected void decreaseBufferBy(double amount) {
        if (this.buffer != 0) {
            this.buffer = (float) Math.max(0, this.buffer - amount);
        }
    }

    public void resetBuffer() {
        this.buffer = 0;
    }

    protected double getBuffer() {
        return this.buffer;
    }


    private void sendPunishWebhook(String player, String reason) {
        try {
            String webhookUrl = Config.Setting.WEBHOOK_LINK.getString();

            if (webhookUrl == null || webhookUrl.trim().isEmpty()) {
                System.out.println("Invalid webhook URL, failed to send punishment message. please check your configuration");
                return;
            }

            URL url;
            try {
                url = new URL(webhookUrl);
            } catch (Exception e) {
                System.out.println("Invalid webhook URL, failed to send punishment message. please check your configuration");
                return;
            }

            String title = "";
            String description = "";

            if (reason.equalsIgnoreCase("BAN")) {
                title = "Player punished | " + player;
                description = "**" + player + "** has been punished for cheating";
            } else if (reason.equalsIgnoreCase("KICK")) {
                title = "Player kicked | " + player;
                description = "**" + player + "** has been kicked/timed out.";
            }

            String json = "{"
                    + "\"embeds\": [{"
                    + "\"title\": \"" + title + "\","
                    + "\"description\": \"" + description + "\","
                    + "\"color\": 16711680"
                    + "}]"
                    + "}";

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
            }

            conn.getInputStream().close();
            conn.disconnect();
        } catch (Exception ignored) {
            System.out.println("Invalid webhook URL, failed to send punishment message. please check your configuration");
        }
    }

}