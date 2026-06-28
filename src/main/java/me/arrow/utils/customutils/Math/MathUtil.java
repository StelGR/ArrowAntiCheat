package me.arrow.utils.customutils.Math;

import com.google.common.util.concurrent.AtomicDouble;
import me.arrow.managers.profile.Profile;
import me.arrow.utils.custom.CustomLocation;
import me.arrow.utils.custom.PotionType;
import me.arrow.utils.customutils.PlayerLocation;
import me.arrow.utils.customutils.Tuple;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MathUtil {


    public static Map<EntityType, Vector> entityDimensions;


    public MathUtil() {
        entityDimensions = new HashMap<>();
        entityDimensions.put(EntityType.WOLF, new Vector(0.31, 0.8, 0.31));
        entityDimensions.put(EntityType.SHEEP, new Vector(0.45, 1.3, 0.45));
        entityDimensions.put(EntityType.COW, new Vector(0.45, 1.3, 0.45));
        entityDimensions.put(EntityType.PIG, new Vector(0.45, 0.9, 0.45));
        entityDimensions.put(EntityType.WITCH, new Vector(0.31, 1.95, 0.31));
        entityDimensions.put(EntityType.BLAZE, new Vector(0.31, 1.8, 0.31));
        entityDimensions.put(EntityType.PLAYER, new Vector(0.3, 1.8, 0.3));
        entityDimensions.put(EntityType.VILLAGER, new Vector(0.31, 1.8, 0.31));
        entityDimensions.put(EntityType.CREEPER, new Vector(0.31, 1.8, 0.31));
        entityDimensions.put(EntityType.GIANT, new Vector(1.8, 10.8, 1.8));
        entityDimensions.put(EntityType.SKELETON, new Vector(0.31, 1.8, 0.31));
        entityDimensions.put(EntityType.ZOMBIE, new Vector(0.31, 1.8, 0.31));
        entityDimensions.put(EntityType.SNOW_GOLEM, new Vector(0.35, 1.9, 0.35));
        entityDimensions.put(EntityType.HORSE, new Vector(0.7, 1.6, 0.7));
        entityDimensions.put(EntityType.ENDER_DRAGON, new Vector(1.5, 1.5, 1.5));

        entityDimensions.put(EntityType.ENDERMAN, new Vector(0.31, 2.9, 0.31));
        entityDimensions.put(EntityType.CHICKEN, new Vector(0.2, 0.7, 0.2));
        entityDimensions.put(EntityType.OCELOT, new Vector(0.31, 0.7, 0.31));
        entityDimensions.put(EntityType.SPIDER, new Vector(0.7, 0.9, 0.7));
        entityDimensions.put(EntityType.WITHER, new Vector(0.45, 3.5, 0.45));
        entityDimensions.put(EntityType.IRON_GOLEM, new Vector(0.7, 2.9, 0.7));
        entityDimensions.put(EntityType.GHAST, new Vector(2, 4, 2));
    }

//    public static Vec3 getVectorForRotation(float pitch, float yaw, Profile data) {
//        float f = pitch * (float) (Math.PI / 180.0);
//        float f1 = -yaw * (float) (Math.PI / 180.0);
//        float f2 = MathHelper.cos(f1);
//        float f3 = MathHelper.sin(f1);
//        float f4 = MathHelper.cos(f);
//        float f5 = MathHelper.sin(f);
//        return new Vec3(f3 * f4, -f5, f2 * f4);
//    }

    public static List<Double> dequeTranslator(Deque<Integer> deque) {
        List<Double> list = new ArrayList<>();
        for (Integer value : deque) {
            list.add((double) value);
        }
        return list;
    }

    public static long LCM(long a, long b) {
        return (a * b) / GCF(a, b);
    }

    public static float distanceBetweenAngles(final float alpha, final float beta) {
        final float alphaX = alpha % 360.0f;
        final float betaX = beta % 360.0f;
        final float delta = Math.abs(alphaX - betaX);
        return (float)Math.abs(Math.min(360.0 - delta, delta));
    }

    public static double angleOf(final double minX, final double minZ, final double maxX, final double maxZ) {
        final double deltaY = minZ - maxZ;
        final double deltaX = maxX - minX;
        final double result = Math.toDegrees(Math.atan2(deltaY, deltaX));
        return (result < 0.0) ? (360.0 + result) : result;
    }

    public static double getDistanceBetweenAngles360(final double alpha, final double beta) {
        final double abs = Math.abs(alpha % 360.0 - beta % 360.0);
        return Math.abs(Math.min(360.0 - abs, abs));
    }

    public static double getDistanceBetweenAngles360Raw(final double alpha, final double beta) {
        return Math.abs(alpha % 360.0 - beta % 360.0);
    }

    public static float getAttributeSpeed(Profile data, boolean sprinting) {
        // Speed is multiplied by 2 for some reason. Thanks CraftBukkit. Fuck you.
        float attributeSpeed = data.getPlayer().getWalkSpeed() / 2.F;

        if (sprinting)
            attributeSpeed *= 1.3F;

        final int speedAmplifier = data.getPotionData().getPotionEffectLevel(PotionType.SPEED);

        if (speedAmplifier > 0) {
            attributeSpeed *= 1.F + (speedAmplifier * 0.2F);
        }

        return attributeSpeed;
    }

    public static long GCF(long a, long b) {
        if (b == 0) {
            return a;
        } else {
            return (GCF(b, a % b));
        }
    }

    public static double getKurtosis(Collection<? extends Number> values) {
        double n = values.size();

        if (n < 3)
            return Double.NaN;

        double average = getAverage(values);
        double stDev = getStandardDeviation(values);

        AtomicDouble accum = new AtomicDouble(0D);

        values.forEach(delay -> accum.getAndAdd(Math.pow(delay.doubleValue() - average, 4D)));

        return n * (n + 1) / ((n - 1) * (n - 2) * (n - 3)) *
                (accum.get() / Math.pow(stDev, 4D)) - 3 *
                Math.pow(n - 1, 2D) / ((n - 2) * (n - 3));
    }

    public static Tuple<List<Double>, List<Double>> getOutliers(Collection<? extends Number> collection) {
        List<Double> values = new ArrayList<>();

        for (Number number : collection) {
            values.add(number.doubleValue());
        }

        if (values.size() < 4) return new Tuple<>(new ArrayList<>(), new ArrayList<>());

        double q1 = getMedian(values.subList(0, values.size() / 2)),
                q3 = getMedian(values.subList(values.size() / 2, values.size()));
        double iqr = Math.abs(q1 - q3);

        double lowThreshold = q1 - 1.5 * iqr, highThreshold = q3 + 1.5 * iqr;

        Tuple<List<Double>, List<Double>> tuple = new Tuple<>(new ArrayList<>(), new ArrayList<>());

        for (Double value : values) {
            if (value < lowThreshold) tuple.one.add(value);
            else if (value > highThreshold) tuple.two.add(value);
        }

        return tuple;
    }

    public static double getSkewness(Iterable<? extends Number> iterable) {
        double sum = 0;
        int buffer = 0;

        List<Double> numberList = new ArrayList<>();

        for (Number num : iterable) {
            sum += num.doubleValue();
            buffer++;

            numberList.add(num.doubleValue());
        }

        Collections.sort(numberList);

        double mean = sum / buffer;
        double median = (buffer % 2 != 0) ? numberList.get(buffer / 2) : (numberList.get((buffer - 1) / 2) + numberList.get(buffer / 2)) / 2;

        return 3 * (mean - median) / deviationSquared(iterable);
    }

    public static double deviationSquared(Iterable<? extends Number> iterable) {
        double n = 0.0;
        int n2 = 0;

        for (Number anIterable : iterable) {
            n += (anIterable).doubleValue();
            ++n2;
        }

        double n3 = n / n2;
        double n4 = 0.0;

        for (Number anIterable : iterable) {
            n4 += Math.pow(anIterable.doubleValue() - n3, 2.0);
        }

        return (n4 == 0.0) ? 0.0 : (n4 / (n2 - 1));
    }

    public static double getMedian(Iterable<? extends Number> iterable) {
        List<Double> data = new ArrayList<>();

        for (Number number : iterable) {
            data.add(number.doubleValue());
        }

        return getMedian(data);
    }

    public static double getMedian(List<Double> data) {
        if (data.size() > 1) {
            if (data.size() % 2 == 0)
                return (data.get(data.size() / 2) + data.get(data.size() / 2 - 1)) / 2;
            else
                return data.get(Math.round(data.size() / 2f));
        }
        return 0;
    }

    public static Vector getDirection(PlayerLocation loc) {
        Vector vector = new Vector();
        double rotX = loc.getYaw();
        double rotY = loc.getPitch();
        vector.setY(-Math.sin(Math.toRadians(rotY)));
        double xz = Math.cos(Math.toRadians(rotY));
        vector.setX(-xz * Math.sin(Math.toRadians(rotX)));
        vector.setZ(xz * Math.cos(Math.toRadians(rotX)));
        return vector;
    }

    public static Vector getDirection(CustomLocation loc) {
        Vector vector = new Vector();
        double rotX = loc.getYaw();
        double rotY = loc.getPitch();
        vector.setY(-Math.sin(Math.toRadians(rotY)));
        double xz = Math.cos(Math.toRadians(rotY));
        vector.setX(-xz * Math.sin(Math.toRadians(rotX)));
        vector.setZ(xz * Math.cos(Math.toRadians(rotX)));
        return vector;
    }

    public static double yawCheck(double yaw, double lastYaw) {
        double perc_value = lastYaw;
        double numb_value = yaw;

        double rslt_value;
        rslt_value = perc_value * numb_value / 100.0;
        rslt_value = 1000.0 * rslt_value / 1000.0;
        return Double.parseDouble(String.valueOf(rslt_value));
    }

    /**
     * Returns the signed angle (degrees) from the player's facing direction to the movement vector.
     *  - 0 means movement exactly forward
     *  - positive/negative values indicate direction to the right/left depending on atan2 sign
     *  - range is [-180, 180]
     */
    public static float getMoveAngle(CustomLocation from, CustomLocation to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();

        // no movement
        if (Math.abs(dx) < 1e-8 && Math.abs(dz) < 1e-8) return 0f;

        float yaw = to.getYaw();
        double yawRad = Math.toRadians(yaw);

        // forward vector in XZ plane (Minecraft convention)
        double fx = -Math.sin(yawRad);
        double fz =  Math.cos(yawRad);

        // normalize movement vector
        double mvLen = Math.hypot(dx, dz);
        if (mvLen < 1e-8) return 0f;
        double mvx = dx / mvLen;
        double mvz = dz / mvLen;

        // dot and 2D cross to get signed angle
        double dot = fx * mvx + fz * mvz;               // cos(theta)
        double cross = fx * mvz - fz * mvx;             // sin(theta)

        // clamp dot just in case of tiny FP error
        dot = Math.max(-1.0, Math.min(1.0, dot));

        double angleDeg = Math.toDegrees(Math.atan2(cross, dot)); // in [-180,180]
        return (float) angleDeg;
    }

    public static double angle(Vector a, Vector b) {
        double dot = Math.min(Math.max(a.dot(b) / (a.length() * b.length()), -1.0), 1.0);
        return Math.acos(dot);
    }

    /** convenience absolute-angle */
    public static float getMoveAngleAbs(CustomLocation from, CustomLocation to) {
        return Math.abs(getMoveAngle(from, to));
    }


    // Improved angle wrapper
    public static float wrapAngleTo180_float(float angle) {
        angle = (angle + 180.0f) % 360.0f;
        if (angle < 0) angle += 360.0f;
        return angle - 180.0f;
    }

    public static BigDecimal round(double value, int i) {
        return new BigDecimal(value).setScale(i, RoundingMode.HALF_UP);
    }

    public static double roundToDouble(double value, int i) {
        return round(value, i).doubleValue();
    }

    public static double getCPS(Collection<? extends Number> values) {
        return 20 / getAverage(values);
    }

    public static float getBaseSpeed(Player player) {
        return 0.26f + (getPotionEffectLevel(player, PotionEffectType.SPEED) * 0.03001f) + ((player.getWalkSpeed() - 0.2f) * 1.6f);
    }

    public static float getBaseSpeed_2(Player player) {
        return 0.23f + (getPotionEffectLevel(player, PotionEffectType.SPEED) * 0.062f) + ((player.getWalkSpeed() - 0.2f) * 1.6f);
    }

    public static float getWalkSpeed(Player player) {
        return (getPotionEffectLevel(player, PotionEffectType.SPEED) * 0.0260001f) + player.getWalkSpeed() * 0.65F;
    }
    public static int getPotionEffectLevel(Player player, PotionEffectType pet) {
        for (PotionEffect pe : player.getActivePotionEffects()) {
            if (pe.getType().getName().equalsIgnoreCase(pet.getName())) {
                return pe.getAmplifier() + 1;
            }
        }
        return 0;
    }

   public static double gcd(double a, double b) {
       if (a < b) {
           return gcd(b, a);
       } else if (Math.abs(b) < 0.001) {
           return a;
       } else {
           return gcd(b, a - Math.floor(a / b) * b);
       }
   }

    public static double getAverage(Collection<? extends Number> values) {
        return values.stream()
                .mapToDouble(Number::doubleValue)
                .average()
                .orElse(0D);
    }

    public static double getStandardDeviation(Collection<? extends Number> values) {
        double average = getAverage(values);

        AtomicDouble variance = new AtomicDouble(0D);

        values.forEach(delay -> variance.getAndAdd(Math.pow(delay.doubleValue() - average, 2D)));

        return Math.sqrt(variance.get() / values.size());
    }


    public static long convertToNanos(long number) {
        return TimeUnit.MILLISECONDS.toNanos(number);
    }

    public static int floor(double var0) {
        int var2 = (int) var0;
        return var0 < var2 ? var2 - 1 : var2;
    }

    public static double movingFlyingV3(Profile user, boolean blockChecking) {
        CustomLocation to =  user.getMovementData().getLocation();
        CustomLocation from = user.getMovementData().getLastLocation();

        double preD = 0.01D;

        double mx = to.getX() - from.getX();
        double mz = to.getZ() - from.getZ();

        float motionYaw = (float) (Math.atan2(mz, mx) * 180.0D / Math.PI) - 90.0F;

        int direction;

        motionYaw -= to.getYaw();

        while (motionYaw > 360.0F)
            motionYaw -= 360.0F;
        while (motionYaw < 0.0F)
            motionYaw += 360.0F;

        motionYaw /= 45.0F;

        float moveS = 0.0F;
        float moveF = 0.0F;

        if (Math.abs(Math.abs(mx) + Math.abs(mz)) > preD) {
            direction = (int) new BigDecimal(motionYaw).setScale(1, RoundingMode.HALF_UP).doubleValue();

            if (direction == 1) {
                moveF = 1F;
                moveS = -1F;
            } else if (direction == 2) {
                moveS = -1F;
            } else if (direction == 3) {
                moveF = -1F;
                moveS = -1F;
            } else if (direction == 4) {
                moveF = -1F;
            } else if (direction == 5) {
                moveF = -1F;
                moveS = 1F;
            } else if (direction == 6) {
                moveS = 1F;
            } else if (direction == 7) {
                moveF = 1F;
                moveS = 1F;
            } else if (direction == 8) {
                moveF = 1F;
            } else if (direction == 0) {
                moveF = 1F;
            }
        }

        moveS *= 0.98F;
        moveF *= 0.98F;

        float strafe = 1F, forward = 1F;
        float f = strafe * strafe + forward * forward;

        if (blockChecking && user.getPredictionData().isUseSword()) {
            strafe *= 0.2F;
            forward *= 0.2F;
        }

        if (user.getActionData().isSneaking() && !user.getActionData().isSprinting()) {
            strafe *= 0.3F;
            forward *= 0.3F;
        }

        float friction;

        float var3 = (0.6F * 0.91F);
        float getAIMoveSpeed = 0.13000001F;

        if (user.getPotionData().getSpeedTicks() > 0) {
            switch (user.getPotionData().getPotionEffectLevel(PotionType.SPEED)) {
                case 0: {
                    getAIMoveSpeed = 0.23400002F;
                    break;
                }
                case 1: {
                    getAIMoveSpeed = 0.156F;
                    break;
                }
                case 2: {
                    getAIMoveSpeed = 0.18200001F;
                    break;
                }
                case 3: {
                    getAIMoveSpeed = 0.208F;
                    break;
                }
                case 4: {
                    getAIMoveSpeed = 0.23400001F;
                    break;
                }
            }
        }

        float var4 = 0.16277136F / (var3 * var3 * var3);

        if (user.getMovementData().isOnGround()) {
            friction = getAIMoveSpeed * var4;
        } else {
            friction = 0.026F;
        }

        float f4 = 0.026F;
        float f5 = 0.8F;

        if (user.getMovementData().isNearWater()) {
            if (user.getPlayer().getInventory().getBoots() != null
                    && user.getPlayer().getInventory().getBoots().getEnchantments() != null) {

                float f3 = user.getPlayer().getInventory().getBoots().getEnchantmentLevel(Enchantment.DEPTH_STRIDER);

                if (f3 > 3.0F) {
                    f3 = 3.0F;
                }

                if (!user.getMovementData().isLastOnGround()) {
                    f3 *= 0.5F;
                }

                if (f3 > 0.0F) {
                    f5 += (0.54600006F - f5) * f3 / 3.0F;
                    f4 += (getAIMoveSpeed - f4) * f3 / 3.0F;
                }

                friction = f4;

                user.getPredictionData().setBlockFriction(f5);
            }
        }

        if (f >= 1.0E-4F) {
            f = (float) Math.sqrt(f);
            if (f < 1.0F) {
                f = 1.0F;
            }
            f = friction / f;
            strafe = strafe * f;
            forward = forward * f;
            float f1 = MathHelper.sin(to.getYaw() * (float) Math.PI / 180.0F);
            float f2 = MathHelper.cos(to.getYaw() * (float) Math.PI / 180.0F);
            float motionXAdd = (strafe * f2 - forward * f1);
            float motionZAdd = (forward * f2 + strafe * f1);
            return Math.hypot(motionXAdd, motionZAdd);
        }

        return 0;
    }

    public static long gcd(long current, long last) {
        if (last <= 16384) return current;
        return gcd(last, current % last);
    }


    public static double[] moveFlying(double motionX, double motionZ, float strafe, float forward, float friction, float yaw) {
        float f = strafe * strafe + forward * forward;

        if (f >= 1.0E-4F) {
            f = MathHelper.sqrt_float(f);

            if (f < 1.0F) {
                f = 1.0F;
            }

            f = friction / f;
            strafe = strafe * f;
            forward = forward * f;
            float f1 = MathHelper.sin(yaw * (float) Math.PI / 180.0F);
            float f2 = MathHelper.cos(yaw * (float) Math.PI / 180.0F);
            motionX += strafe * f2 - forward * f1;
            motionZ += forward * f2 + strafe * f1;
        }

        return new double[]{motionX, motionZ};
    }


    public static double getDevation(final Collection<? extends Number> nums){
        if (nums.isEmpty()) return 0D;

        return Math.sqrt((getVariance(nums) / (nums.size() - 1)));
    }

    public static long getAverageLong(final Collection<Long> nums){
        if (nums.isEmpty()) return 0L;

        return getSumLong(nums) / nums.size();
    }

    public static long getSumLong(final Collection<Long> nums){
        if (nums.isEmpty()) return 0L;

        long sum = 0;

        for (long num : nums) sum += num;

        return sum;
    }

    public static double getVariance(final Collection<? extends Number> data) {
        if (data.isEmpty()) return 0D;

        int count = 0;

        double sum = 0.0;
        double varience = 0.0;

        double average;

        for (final Number number : data){
            sum += number.doubleValue();
            ++count;
        }
        average =  sum / count;

        for (final Number number : data){
            varience += Math.pow(number.doubleValue() - average, 2.0);
        }

        return varience;
    }

    public static boolean isScientificNotation(final Float f) {
        return f.toString().contains("E");
    }
}
