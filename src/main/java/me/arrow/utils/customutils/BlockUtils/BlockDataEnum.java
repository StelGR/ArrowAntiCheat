package me.arrow.utils.customutils.BlockUtils;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import lombok.val;
import me.arrow.utils.custom.materials.MaterialType;
import me.arrow.utils.customutils.CollisionFactory;
import me.arrow.utils.customutils.Hitboxes.*;
import me.arrow.utils.customutils.Hitboxes.BlockHitboxes.*;
import me.arrow.utils.customutils.Hitboxes.GeneralHitboxes.*;
import me.arrow.utils.customutils.Math.Reflections;
import me.arrow.utils.customutils.Math.WrappedShit.WrappedClass;
import me.arrow.utils.customutils.MiscUtils;
import me.arrow.utils.customutils.Hitboxes.BlockHitboxes.TrapDoorHandler;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.material.Cake;
import org.bukkit.material.Gate;
import org.bukkit.material.MaterialData;
import org.bukkit.material.Vine;

import java.util.*;

public enum BlockDataEnum {
    _VINE((v, block) -> {
        Vine data = (Vine) block.getType().getNewData(block.getData());

        if(data.isOnFace(BlockFace.UP))
            return new SimpleCollisionBox(0., 0.9375, 0.,
                    1., 1., 1.);

        if(data.isOnFace(BlockFace.NORTH))
            return new SimpleCollisionBox(0., 0., 0.,
                    1., 1., 0.0625);

        if(data.isOnFace(BlockFace.EAST))
            return new SimpleCollisionBox(0.9375, 0., 0.,
                    1., 1., 1.);

        if(data.isOnFace(BlockFace.SOUTH))
            return new SimpleCollisionBox(0., 0., 0.9375,
                    1., 1., 1.);

        if(data.isOnFace(BlockFace.WEST))
            return new SimpleCollisionBox(0., 0., 0.,
                    0.0625, 1., 1.);

        return new SimpleCollisionBox(0,0,0,1.,1.,1.);
    }, fromTypes(MaterialType.CLIMBABLE)),

    _LIQUID(new SimpleCollisionBox(0, 0, 0, 1f, 0.9f, 1f),
            fromTypes(MaterialType.LIQUID)),

    _BREWINGSTAND(new ComplexCollisionBox(
            new SimpleCollisionBox(0, 0, 0, 1, 0.125, 1),                      //base
            new SimpleCollisionBox(0.4375, 0.0, 0.4375, 0.5625, 0.875, 0.5625) //top
    ), fromTypes(MaterialType.BREWING_STAND)), // <-- add BREWING_STAND to MaterialType (see below)

    _ANVIL((protocol, b) -> {
        BlockState state = b.getState();
        b.setType(Material.ANVIL);
        int dir = state.getData().getData() & 0b01;
        CollisionBox box;
        if (dir == 1) {
            box = new SimpleCollisionBox(0.0F, 0.0F, 0.125F, 1.0F, 1.0F, 0.875F);
        } else {
            box = new SimpleCollisionBox(0.125F, 0.0F, 0.0F, 0.875F, 1.0F, 1.0F);
        }
        return box;
    }, fromTypes(MaterialType.HALF_BLOCK)), // ANVIL is contained in HALF_BLOCK

    _WALL(new DynamicWall(), fromTypes(MaterialType.WALL)),

    _SKULL((protocol, b) -> {
        int rotation = b.getState().getData().getData() & 7;

        return switch (rotation) {
            case 2 -> new SimpleCollisionBox(0.25F, 0.25F, 0.5F, 0.75F, 0.75F, 1.0F);
            case 3 -> new SimpleCollisionBox(0.25F, 0.25F, 0.0F, 0.75F, 0.75F, 0.5F);
            case 4 -> new SimpleCollisionBox(0.5F, 0.25F, 0.25F, 1.0F, 0.75F, 0.75F);
            case 5 -> new SimpleCollisionBox(0.0F, 0.25F, 0.25F, 0.5F, 0.75F, 0.75F);
            default -> new SimpleCollisionBox(0.25F, 0.0F, 0.25F, 0.75F, 0.5F, 0.75F);
        };
    }, fromTypes(MaterialType.HALF_BLOCK)), // skulls/heads are in HALF_BLOCK

    _DOOR(new DoorHandler(), MaterialType.DOOR),

    _HOPPER(new HopperBounding(), fromTypes(MaterialType.HALF_BLOCK)),

    _CAKE((protocol, block) -> {
        Cake cake = (Cake) block.getType().getNewData(block.getData());

        double f1 = (1 + cake.getSlicesEaten() * 2) / 16D;

        return new SimpleCollisionBox(f1, 0, 0.0625, 1 - 0.0625, 0.5, 1 - 0.0625);
    }, fromTypes(MaterialType.HALF_BLOCK)),

    _LADDER((protocol, b) -> {
        CollisionBox box = NoCollisionBox.INSTANCE;
        float var3 = 0.125F;

        byte data = b.getState().getData().getData();
        if (data == 2) {
            box = new SimpleCollisionBox(0.0F, 0.0F, 1.0F - var3, 1.0F, 1.0F, 1.0F);
        } else if (data == 3) {
            box = new SimpleCollisionBox(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, var3);
        } else if (data == 4) {
            box = new SimpleCollisionBox(1.0F - var3, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F);
        } else if (data == 5) {
            box = new SimpleCollisionBox(0.0F, 0.0F, 0.0F, var3, 1.0F, 1.0F);
        }
        return box;
    }, fromTypes(MaterialType.CLIMBABLE)),

    _FENCE_GATE((protocol, b) -> {
        byte var5 = b.getState().getData().getData();

        CollisionBox box = NoCollisionBox.INSTANCE;
        if (!((Gate) b.getState().getData()).isOpen()) {
            if (var5 != 2 && var5 != 0) {
                box = new SimpleCollisionBox(0.375F, 0.0F, 0.0F, 0.625F, 1.5F, 1.0F);
            } else {
                box = new SimpleCollisionBox(0.0F, 0.0F, 0.375F, 1.0F, 1.5F, 0.625F);
            }
        }
        return box;
    }, fromTypes(MaterialType.FENCE)),

    _FENCE(new DynamicFence(), fromTypes(MaterialType.FENCE)),

    _PANE(new DynamicPane(), fromTypes(MaterialType.PANE)), // <-- add PANE to MaterialType (see below)

    _SNOW((protocol, b) -> {
        MaterialData state = b.getState().getData();
        int height = (state.getData() & 0b1111);
        if (height == 0) return new SimpleCollisionBox(0, 0, 0, 1, 0, 1); // return NoCollisionBox.INSTANCE;
        return new SimpleCollisionBox(0, 0, 0, 1, height * 0.125, 1);
    }, fromTypes(MaterialType.SNOW)),

    _SLAB((protocol, b) -> {
        MaterialData state = b.getState().getData();
        if ((state.getData() & 8) == 0)
            return new SimpleCollisionBox(0, 0, 0, 1, .5, 1);
        else return new SimpleCollisionBox(0, .5, 0, 1, 1, 1);
    }, fromTypes(MaterialType.HALF_BLOCK)),

    _STAIR((protocol, b) -> {
        MaterialData state = b.getState().getData();
        boolean inverted = (state.getData() & 4) != 0;
        int dir = (state.getData() & 0b11);
        SimpleCollisionBox top;
        SimpleCollisionBox bottom = new SimpleCollisionBox(0, 0, 0, 1, .5, 1);
        if (dir == 0) top = new SimpleCollisionBox(.5, .5, 0, 1, 1, 1);
        else if (dir == 1) top = new SimpleCollisionBox(0, .5, 0, .5, 1, 1);
        else if (dir == 2) top = new SimpleCollisionBox(0, .5, .5, 1, 1, 1);
        else top = new SimpleCollisionBox(0, .5, 0, 1, 1, .5);
        if (inverted) {
            top.offset(0, -.5, 0);
            bottom.offset(0, .5, 0);
        }
        return new ComplexCollisionBox(top, bottom);
    }, fromTypes(MaterialType.STAIRS)),

    _CHEST(new SimpleCollisionBox(0, 0, 0, 1, 1 - 0.125, 1).expand(-0.125, 0, -0.125),
            fromTypes(MaterialType.HALF_BLOCK)),
    _ETABLE(new SimpleCollisionBox(0, 0, 0, 1, 1 - 0.25, 1),
            fromTypes(MaterialType.HALF_BLOCK)),
    _FRAME(new SimpleCollisionBox(0, 0, 0, 1, 1 - (0.0625 * 3), 1),
            fromTypes(MaterialType.HALF_BLOCK)),

    _CARPET(new SimpleCollisionBox(0.0F, 0.0F, 0.0F, 1.0F, 0.0625F, 1.0F), fromTypes(MaterialType.HALF_BLOCK)),
    _Daylight(new SimpleCollisionBox(0.0F, 0.0F, 0.0F, 1.0F, 0.375, 1.0F), fromTypes(MaterialType.HALF_BLOCK)),
    _LILIPAD((v, b) -> {
        if (v.isOlderThan(ClientVersion.V_1_9))
            return new SimpleCollisionBox(0.0f, 0.0F, 0.0f, 1.0f, 0.015625F, 1.0f);
        return new SimpleCollisionBox(0.0625, 0.0F, 0.0625, 0.9375, 0.015625F, 0.9375);
    }, fromTypes(MaterialType.HALF_BLOCK)),

    _BED(new SimpleCollisionBox(0.0F, 0.0F, 0.0F, 1.0F, 0.5625, 1.0F),
            fromTypes(MaterialType.BED)),

    _TRAPDOOR(new TrapDoorHandler(), fromTypes(MaterialType.TRAPDOOR)),

    _STUPID(new SimpleCollisionBox(0.0F, 0.0F, 0.0F, 1.0F, 0.125F, 1.0F),
            fromTypes(MaterialType.HALF_BLOCK)),

    _STRUCTURE_VOID(new SimpleCollisionBox(0.375, 0.375, 0.375,
            0.625, 0.625, 0.625),
            fromTypes(MaterialType.STRUCTURE_VOID)), // <-- add STRUCTURE_VOID to MaterialType

    _END_ROD(new DynamicRod(), fromTypes(MaterialType.HALF_BLOCK)),
    _CAULDRON(new CouldronBounding(), fromTypes(MaterialType.HALF_BLOCK)),
    _CACTUS(new SimpleCollisionBox(0.0625, 0, 0.0625,
            1 - 0.0625, 1 - 0.0625, 1 - 0.0625), fromTypes(MaterialType.CACTUS)), // <-- add CACTUS

    _PISTON_BASE(new PistonBaseCollision(), fromTypes(MaterialType.PISTON)),

    _PISTON_ARM(new PistonDickCollision(), fromTypes(MaterialType.PISTON)),

    _SOULSAND(new SimpleCollisionBox(0, 0, 0, 1, 0.875, 1),
            fromTypes(MaterialType.SOUL_BLOCK)),
    _PICKLE((version, block) -> {
        val wrapped = new WrappedClass(block.getClass());
        val getBlockData = wrapped.getMethod("getBlockData");
        val pickleClass = Reflections.getNMSClass("SeaPickle");
        Object pickle = getBlockData.invoke(block);

        int pickles = pickleClass.getMethod("getPickles").invoke(pickle);

        return switch (pickles) {
            case 1 -> new SimpleCollisionBox(6.0D / 15, 0.0, 6.0D / 15,
                    10.0D / 15, 6.0D / 15, 10.0D / 15);
            case 2 -> new SimpleCollisionBox(3.0D / 15, 0.0D, 3.0D / 15,
                    13.0D / 15, 6.0D / 15, 13.0D / 15);
            case 3 -> new SimpleCollisionBox(2.0D / 15, 0.0D, 2.0D / 15,
                    14.0D / 15, 6.0D / 15, 14.0D / 15);
            case 4 -> new SimpleCollisionBox(2.0D / 15, 0.0D, 2.0D / 15,
                    14.0D / 15, 7.0D / 15, 14.0D / 15);
            default -> NoCollisionBox.INSTANCE;
        };
    }, fromTypes(MaterialType.HALF_BLOCK)),
    _POT(new SimpleCollisionBox(0.3125, 0.0, 0.3125, 0.6875, 0.375, 0.6875),
            fromTypes(MaterialType.HALF_BLOCK)),

    _WALL_SIGN((version, block) -> {

        byte data = block.getData();
        double var4 = 0.28125;
        double var5 = 0.78125;
        double var6 = 0;
        double var7 = 1.0;
        double var8 = 0.125;

        BlockFace face = switch (data) {
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.NORTH;
            case 4 -> BlockFace.EAST;
            case 5 -> BlockFace.WEST;
            default -> BlockFace.DOWN;
        };

        face = !face.equals(BlockFace.DOWN) ? face.getOppositeFace() : BlockFace.DOWN;

        return switch (face) {
            case NORTH -> new SimpleCollisionBox(var6, var4, 1.0 - var8, var7, var5, 1.0);
            case SOUTH -> new SimpleCollisionBox(var6, var4, 0.0, var7, var5, var8);
            case WEST -> new SimpleCollisionBox(1.0 - var8, var4, var6, 1.0, var5, var7);
            case EAST -> new SimpleCollisionBox(0.0, var4, var6, var8, var5, var7);
            default -> new SimpleCollisionBox(0, 0, 0, 1, 1, 1);
        };
    }, fromTypes(MaterialType.HALF_BLOCK)),

    _SIGN(new SimpleCollisionBox(0.25, 0.0, 0.25, 0.75, 1.0, 0.75),
            fromTypes(MaterialType.HALF_BLOCK)),
    _BUTTON((version, block) -> {
        BlockFace face;
        switch(block.getData() & 7) {
            case 0:
                face = BlockFace.UP;
                break;
            case 1:
                face = BlockFace.WEST;
                break;
            case 2:
                face = BlockFace.EAST;
                break;
            case 3:
                face = BlockFace.NORTH;
                break;
            case 4:
                face = BlockFace.SOUTH;
                break;
            case 5:
                face = BlockFace.DOWN;
                break;
            default:
                return NoCollisionBox.INSTANCE;
        }

        face = face.getOppositeFace();
        boolean flag = (block.getData() & 8) == 8; //is powered;
        double f2 = (float)(flag ? 1 : 2) / 16.0;
        return switch (face) {
            case EAST -> new SimpleCollisionBox(0.0, 0.375, 0.3125, f2, 0.625, 0.6875);
            case WEST -> new SimpleCollisionBox(1.0 - f2, 0.375, 0.3125, 1.0, 0.625, 0.6875);
            case SOUTH -> new SimpleCollisionBox(0.3125, 0.375, 0.0, 0.6875, 0.625, f2);
            case NORTH -> new SimpleCollisionBox(0.3125, 0.375, 1.0 - f2, 0.6875, 0.625, 1.0);
            case UP -> new SimpleCollisionBox(0.3125, 0.0, 0.375, 0.6875, 0.0 + f2, 0.625);
            case DOWN -> new SimpleCollisionBox(0.3125, 1.0 - f2, 0.375, 0.6875, 1.0, 0.625);
            default -> NoCollisionBox.INSTANCE;
        };
    }, fromTypes(MaterialType.HALF_BLOCK)),

    _LEVER((version, block) -> {
        byte data = (byte)(block.getData() & 7);
        BlockFace face;
        switch(data) {
            case 0:
            case 7:
                face = BlockFace.UP;
                break;
            case 1:
                face = BlockFace.WEST;
                break;
            case 2:
                face = BlockFace.EAST;
                break;
            case 3:
                face = BlockFace.NORTH;
                break;
            case 4:
                face = BlockFace.SOUTH;
                break;
            case 5:
            case 6:
                face = BlockFace.DOWN;
                break;
            default:
                return NoCollisionBox.INSTANCE;
        }

        double f = 0.1875;
        return switch (face) {
            case EAST -> new SimpleCollisionBox(0.0, 0.2, 0.5 - f, f * 2.0, 0.8, 0.5 + f);
            case WEST -> new SimpleCollisionBox(1.0 - f * 2.0, 0.2, 0.5 - f, 1.0, 0.8, 0.5 + f);
            case SOUTH -> new SimpleCollisionBox(0.5 - f, 0.2, 0.0, 0.5 + f, 0.8, f * 2.0);
            case NORTH -> new SimpleCollisionBox(0.5 - f, 0.2, 1.0 - f * 2.0, 0.5 + f, 0.8, 1.0);
            case UP -> new SimpleCollisionBox(0.25, 0.0, 0.25, 0.75, 0.6, 0.75);
            case DOWN -> new SimpleCollisionBox(0.25, 0.4, 0.25, 0.75, 1.0, 0.75);
            default -> NoCollisionBox.INSTANCE;
        };
    }, fromTypes(MaterialType.LEVER)), // <-- add LEVER to MaterialType

    _NONE(NoCollisionBox.INSTANCE, Material.TORCH, Material.REDSTONE_TORCH,
            Material.REDSTONE_WALL_TORCH, Material.POWERED_RAIL, Material.RAIL,
            Material.ACTIVATOR_RAIL, Material.DETECTOR_RAIL, Material.AIR, Material.SHORT_GRASS,
            Material.TRIPWIRE, Material.TRIPWIRE_HOOK, Material.REDSTONE_WIRE, Material.TALL_GRASS),

    _NONE2(NoCollisionBox.INSTANCE, Material.ACACIA_PRESSURE_PLATE, Material.BIRCH_PRESSURE_PLATE,
            Material.DARK_OAK_PRESSURE_PLATE, Material.JUNGLE_PRESSURE_PLATE,
            Material.OAK_PRESSURE_PLATE, Material.SPRUCE_PRESSURE_PLATE,
            Material.CRIMSON_PRESSURE_PLATE, Material.WARPED_PRESSURE_PLATE,
            Material.MANGROVE_PRESSURE_PLATE, Material.CHERRY_PRESSURE_PLATE,
            Material.BAMBOO_PRESSURE_PLATE, Material.STONE_PRESSURE_PLATE,
            Material.LIGHT_WEIGHTED_PRESSURE_PLATE, Material.HEAVY_WEIGHTED_PRESSURE_PLATE,
            Material.POLISHED_BLACKSTONE_PRESSURE_PLATE),

    _DEFAULT(new SimpleCollisionBox(0, 0, 0, 1, 1, 1),
            Material.STONE);

    private CollisionBox box;
    private CollisionFactory dynamic;
    private final Material[] materials;

    // ORIGINAL constructors left intact
    BlockDataEnum(CollisionBox box, Material... materials) {
        this.box = box;
        Set<Material> mList = new HashSet<>(Arrays.asList(materials));
        mList.remove(null); // Sets can contain one null
        this.materials = mList.toArray(new Material[0]);
    }

    BlockDataEnum(CollisionFactory dynamic, Material... materials) {
        this.dynamic = dynamic;
        Set<Material> mList = new HashSet<>(Arrays.asList(materials));
        mList.remove(null); // Sets can contain one null
        this.materials = mList.toArray(new Material[0]);
    }

    // NEW overloads that accept MaterialType
    BlockDataEnum(CollisionBox box, MaterialType... types) {
        this(box, fromTypes(types));
    }

    BlockDataEnum(CollisionFactory dynamic, MaterialType... types) {
        this(dynamic, fromTypes(types));
    }

    private static Material[] fromTypes(MaterialType... types) {
        List<Material> mats = new ArrayList<>();
        if (types == null || types.length == 0) return new Material[0];
        for (MaterialType t : types) {
            if (t == null) continue;
            for (String name : t.getValues()) {
                Material m = Material.matchMaterial(name);
                if (m != null) mats.add(m);
            }
        }
        // preserve insertion order and remove duplicates
        Set<Material> set = new LinkedHashSet<>(mats);
        return set.toArray(new Material[0]);
    }

    public CollisionBox getBox(Block block, ClientVersion version) {
        if (this.box != null)
            return this.box.copy().offset(block.getX(), block.getY(), block.getZ());
        return new DynamicCollisionBox(dynamic, block, version).offset(block.getX(), block.getY(), block.getZ());
    }

    public BoundingBox getBoundingBox(Block block, ClientVersion version) {
        CollisionBox box = getBox(block, version);
        return convertToBoundingBox(box);
    }


    public static BoundingBox convertToBoundingBox(CollisionBox cbox) {
        List<SimpleCollisionBox> subBoxes = new ArrayList<>();
        cbox.downCast(subBoxes);

        if (subBoxes.isEmpty()) {
            // fallback if no real box info
            return new BoundingBox(0, 0, 0, 0, 0, 0);
        }

        // Merge all sub-boxes into one bounding box
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        for (SimpleCollisionBox sub : subBoxes) {
            minX = Math.min(minX, sub.xMin);
            minY = Math.min(minY, sub.yMin);
            minZ = Math.min(minZ, sub.zMin);
            maxX = Math.max(maxX, sub.xMax);
            maxY = Math.max(maxY, sub.yMax);
            maxZ = Math.max(maxZ, sub.zMax);
        }

        return new BoundingBox((float) minX, (float) minY, (float) minZ,
                (float) maxX, (float) maxY, (float) maxZ);
    }

    private static final BlockDataEnum[] lookup = new BlockDataEnum[Material.values().length];

    static {
        for (BlockDataEnum data : values()) {
            for (Material mat : data.materials) lookup[mat.ordinal()] = data;
        }
    }

    public static BlockDataEnum getData(Material material) {
        Material matched = MiscUtils.match(material.toString());
        BlockDataEnum data = lookup[matched.ordinal()];
        return data != null ? data : _DEFAULT;
    }

}
