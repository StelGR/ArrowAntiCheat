package me.arrow.utils.custom;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import lombok.Getter;
import me.arrow.utils.TaskUtils;
import me.arrow.utils.fastmath.FastMath;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;

import java.util.function.Consumer;

//this saves alot of performance, i think......

@Getter
public class CustomLocation {

    private final World world;
    private final float yaw, pitch;
    private final long timeStamp;
    private double x, y, z;
    private int blockX, blockY, blockZ;

    public CustomLocation(World world, double x, double y, double z, float yaw, float pitch, long timeStamp) {

        this.world = world;

        this.blockX = FastMath.floorInt(this.x = x);
        this.blockY = FastMath.floorInt(this.y = y);
        this.blockZ = FastMath.floorInt(this.z = z);

        this.yaw = yaw;
        this.pitch = pitch;

        this.timeStamp = timeStamp;
    }

    public CustomLocation(World world, double x, double y, double z, float yaw, float pitch) {

        this.world = world;

        this.blockX = FastMath.floorInt(this.x = x);
        this.blockY = FastMath.floorInt(this.y = y);
        this.blockZ = FastMath.floorInt(this.z = z);

        this.yaw = yaw;
        this.pitch = pitch;

        this.timeStamp = System.currentTimeMillis();
    }

    public CustomLocation(World world, double x, double y, double z) {

        this.world = world;

        this.blockX = FastMath.floorInt(this.x = x);
        this.blockY = FastMath.floorInt(this.y = y);
        this.blockZ = FastMath.floorInt(this.z = z);

        this.yaw = this.pitch = 0.0F;

        this.timeStamp = System.currentTimeMillis();
    }

    public CustomLocation(Location location) {

        this.world = location.getWorld();

        this.blockX = FastMath.floorInt(this.x = location.getX());
        this.blockY = FastMath.floorInt(this.y = location.getY());
        this.blockZ = FastMath.floorInt(this.z = location.getZ());

        this.yaw = location.getYaw();
        this.pitch = location.getPitch();

        this.timeStamp = System.currentTimeMillis();
    }

    public CustomLocation(CustomLocation location) {

        this.world = location.getWorld();

        this.blockX = FastMath.floorInt(this.x = location.getX());
        this.blockY = FastMath.floorInt(this.y = location.getY());
        this.blockZ = FastMath.floorInt(this.z = location.getZ());

        this.yaw = location.getYaw();
        this.pitch = location.getPitch();

        this.timeStamp = System.currentTimeMillis();
    }

    public Vector toVector() {
        return new Vector(this.x, this.y, this.z);
    }

    public Location toBukkit() {
        return new Location(this.world, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    public CustomLocation clone() {
        return new CustomLocation(this.world, this.x, this.y, this.z, this.yaw, this.pitch);
    }

    public void setX(double x) {
        this.blockX = FastMath.floorInt(this.x = x);
    }

    public void setY(double y) {
        this.blockY = FastMath.floorInt(this.y = y);
    }

    public void setZ(double z) {
        this.blockZ = FastMath.floorInt(this.z = z);
    }

    public double distance(CustomLocation other) {
        return FastMath.sqrt(this.distanceSquared(other));
    }

    public double distance(Location other) {
        return FastMath.sqrt(this.distanceSquared(other));
    }

    public double distanceSquared(CustomLocation other) {

        double distX = this.x - other.getX();
        double distY = this.y - other.getY();
        double distZ = this.z - other.getZ();

        return (distX * distX) + (distY * distY) + (distZ * distZ);
    }

    public double distanceSquaredXZ(CustomLocation o) {
        if (o.getWorld() != null && getWorld() != null && o.getWorld() == getWorld()) {
            return NumberConversions.square(this.getX() - o.getX())
                    + NumberConversions.square(this.getZ() - o.getZ());
        }
        return 0.0;
    }

    public double distanceSquared(Location other) {

        double distX = this.x - other.getX();
        double distY = this.y - other.getY();
        double distZ = this.z - other.getZ();

        return (distX * distX) + (distY * distY) + (distZ * distZ);
    }

    public double length() {
        return FastMath.sqrt((this.x * this.x) + (this.y * this.y) + (this.z * this.z));
    }


    public Block getBlock() {
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_13)) {
            return world.getBlockAt(blockX, blockY, blockZ);
        } else {
            return this.clone().toBukkit().getBlock();
        }
    }

    public void getBlockAsync(Consumer<Block> callback) {
        TaskUtils.task(() ->
                callback.accept(world.getBlockAt(blockX, blockY, blockZ))
        );
    }




    public float getAngle(Vector other) {

        final double dot = Math.min(Math.max(
                        (this.x * other.getX() + this.y * other.getY() + this.z * other.getZ())
                                / (this.length() * other.length()),
                        -1.0),
                1.0);

        return (float) FastMath.acos(dot);
    }

    public Vector getDirection() {

        Vector vector = new Vector();

        double x = FastMath.toRadians(this.pitch);

        vector.setY(-FastMath.sin(x));

        double xz = FastMath.cos(x);

        double radiansRotX = FastMath.toRadians(this.yaw);

        vector.setX(-xz * FastMath.sin(radiansRotX));
        vector.setZ(xz * FastMath.cos(radiansRotX));

        return vector;
    }

    public CustomLocation add(CustomLocation loc) {
        this.blockX = FastMath.floorInt(this.x += loc.getX());
        this.blockY = FastMath.floorInt(this.y += loc.getY());
        this.blockZ = FastMath.floorInt(this.z += loc.getZ());
        return this;
    }

    public CustomLocation add(Location loc) {
        this.blockX = FastMath.floorInt(this.x += loc.getX());
        this.blockY = FastMath.floorInt(this.y += loc.getY());
        this.blockZ = FastMath.floorInt(this.z += loc.getZ());
        return this;
    }

    public CustomLocation add(Vector vec) {
        this.blockX = FastMath.floorInt(this.x += vec.getX());
        this.blockY = FastMath.floorInt(this.y += vec.getY());
        this.blockZ = FastMath.floorInt(this.z += vec.getZ());
        return this;
    }

    public CustomLocation add(double x, double y, double z) {
        this.blockX = FastMath.floorInt(this.x += x);
        this.blockY = FastMath.floorInt(this.y += y);
        this.blockZ = FastMath.floorInt(this.z += z);
        return this;
    }

    public CustomLocation subtract(CustomLocation loc) {
        this.blockX = FastMath.floorInt(this.x -= loc.getX());
        this.blockY = FastMath.floorInt(this.y -= loc.getY());
        this.blockZ = FastMath.floorInt(this.z -= loc.getZ());
        return this;
    }

    public CustomLocation subtract(Location loc) {
        this.blockX = FastMath.floorInt(this.x -= loc.getX());
        this.blockY = FastMath.floorInt(this.y -= loc.getY());
        this.blockZ = FastMath.floorInt(this.z -= loc.getZ());
        return this;
    }

    public CustomLocation subtract(Vector vec) {
        this.blockX = FastMath.floorInt(this.x -= vec.getX());
        this.blockY = FastMath.floorInt(this.y -= vec.getY());
        this.blockZ = FastMath.floorInt(this.z -= vec.getZ());
        return this;
    }

    public CustomLocation subtract(double x, double y, double z) {
        this.blockX = FastMath.floorInt(this.x -= x);
        this.blockY = FastMath.floorInt(this.y -= y);
        this.blockZ = FastMath.floorInt(this.z -= z);
        return this;
    }
}