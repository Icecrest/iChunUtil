package me.ichun.mods.ichunutil.common.module.worldportals.common.portal;

import me.ichun.mods.ichunutil.client.render.RendererHelper;
import me.ichun.mods.ichunutil.common.core.util.EntityHelper;
import me.ichun.mods.ichunutil.common.entity.EntityBlock;
import me.ichun.mods.ichunutil.common.iChunUtil;
import me.ichun.mods.ichunutil.common.module.worldportals.common.WorldPortals;
import me.ichun.mods.ichunutil.common.module.worldportals.common.packet.PacketEntityLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRain;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.*;

public abstract class WorldPortal
{
    private EnumFacing faceOn;
    private EnumFacing upDir; //Upwards direction of the portal.

    private QuaternionFormula quaternionFormula; //used to calculate the rotation and the positional offset (as well as motion)

    private AxisAlignedBB plane;
    private AxisAlignedBB flatPlane; //AABB that defines the plane where the magic happens.
    public AxisAlignedBB scanRange;
    public AxisAlignedBB portalInsides;

    private float width;
    private float height;

    private Vec3d position;
    private BlockPos posBlock;

    private HashSet<AxisAlignedBB> collisions;

    private WorldPortal pair;

    public World world;
    public int time;
    public List<Entity> lastScanEntities = new ArrayList<>();
    public HashMap<Entity, Integer> teleportCooldown = new HashMap<>();

    private boolean firstUpdate;

//    private boolean cullRender = true;

    public boolean renderAll = false;

    public WorldPortal(World world)
    {
        this.world = world;
        this.position = new Vec3d(0,0,0);
        this.posBlock = new BlockPos(this.position);

        this.faceOn = EnumFacing.NORTH;
        this.upDir = EnumFacing.UP;

        this.time = 0;
        this.firstUpdate = true;
    }

    public WorldPortal(World world, Vec3d position, EnumFacing faceOn, EnumFacing upDir, float width, float height)
    {
        this.world = world;
        this.position = position;
        this.posBlock = new BlockPos(this.position);

        this.faceOn = faceOn;
        this.upDir = upDir;

        this.width = width;
        this.height = height;

        this.setupAABBs();

        this.time = 0;
        this.firstUpdate = true;
    }

    public abstract float getPlaneOffset();

    public abstract boolean canCollideWithBorders();

    public abstract String owner(); //mod that owns this;

    @SideOnly(Side.CLIENT)
    public abstract void drawPlane(float partialTick);

    public void setFace(EnumFacing faceOut, EnumFacing upDir)
    {
        this.faceOn = faceOut;
        this.upDir = upDir;
        setupAABBs();
    }

    public EnumFacing getFaceOn()
    {
        return faceOn;
    }

    public EnumFacing getUpDir()
    {
        return upDir;
    }

    public BlockPos getPos()
    {
        return posBlock;
    }

    public float getWidth()
    {
        return width;
    }

    public float getHeight()
    {
        return height;
    }

    public void setSize(float width, float height)
    {
        this.width = width;
        this.height = height;
        setupAABBs();
    }

    public float getScanDistance()
    {
        return 3F;
    }

    public void updateWorldPortal()
    {
        if(firstUpdate)
        {
            firstUpdate = false;
        }
        time++;
        if(!canTeleportEntities())
        {
            return;
        }
        Iterator<Map.Entry<Entity, Integer>> ite = teleportCooldown.entrySet().iterator();
        while(ite.hasNext())
        {
            Map.Entry<Entity, Integer> e = ite.next();
            e.setValue(e.getValue() - 1);
            if(e.getValue() < 0)
            {
                WorldPortals.eventHandler.removeMonitoredEntity(e.getKey(), this);
                ite.remove();
            }
        }
        if(!hasPair())
        {
            return;
        }

        EnumFacing faceOn = getFaceOn();
        List<Entity> entitiesInRange = world.getEntitiesWithinAABB(Entity.class, scanRange);
        for(int i = entitiesInRange.size() - 1; i >= 0; i--)
        {
            Entity ent = entitiesInRange.get(i);

            if(isAgainstWall())
            {
                WorldPortals.eventHandler.addMonitoredEntity(ent, this);
            }
            if(!canEntityTeleport(ent))
            {
                entitiesInRange.remove(i);
                continue;
            }
            if(teleportCooldown.containsKey(ent) || ent instanceof EntityPlayerMP && !ent.getEntityWorld().isRemote)
            {
                continue;
            }

            double[] motions = EntityHelper.simulateMoveEntity(ent, ent.motionX, ent.motionY, ent.motionZ);
            Vec3d newEntPos = new Vec3d(ent.posX + motions[0], ent.posY + ent.getEyeHeight() + motions[1], ent.posZ + motions[2]);
            boolean teleport = false;
            AxisAlignedBB teleportPlane = flatPlane;
            float offset = 0.0F; //should I test player width specifically?
            if(isAgainstWall() && ent instanceof EntityPlayer)
            {
                offset = Math.min(0.05F, (float)Math.abs((flatPlane.minX - ent.posX) * faceOn.getXOffset() + (flatPlane.minY - ent.posY) * faceOn.getYOffset() + (flatPlane.minZ - ent.posZ) * faceOn.getZOffset()));
                if(!scanRange.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset).contains(newEntPos) &&
                        portalInsides.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset).contains(newEntPos) &&
                        (faceOn.getAxis().isHorizontal() && ent.getEntityBoundingBox().minY >= flatPlane.minY && ent.getEntityBoundingBox().maxY <= flatPlane.maxY || faceOn.getAxis().isVertical()  && ent.getEntityBoundingBox().minX >= flatPlane.minX && ent.getEntityBoundingBox().maxX <= flatPlane.maxX  && ent.getEntityBoundingBox().minZ >= flatPlane.minZ && ent.getEntityBoundingBox().maxZ <= flatPlane.maxZ) // special casing cause of pushOutOfBlocks for player
                        )
                {
                    teleportPlane = getTeleportPlane(offset);
                    teleport = true;
                }
            }
            else
            {
                if(!scanRange.contains(newEntPos) && portalInsides.contains(newEntPos))
                {
                    teleport = true;
                }
            }

            if(teleport)
            {
                double centerX = (teleportPlane.maxX + teleportPlane.minX) / 2D;
                double centerY = (teleportPlane.maxY + teleportPlane.minY) / 2D;
                double centerZ = (teleportPlane.maxZ + teleportPlane.minZ) / 2D;

                if(pair != null)
                {
                    float[] appliedOffset = getQuaternionFormula().applyPositionalRotation(new float[] { (float)(newEntPos.x - centerX), (float)(newEntPos.y - centerY), (float)(newEntPos.z - centerZ) });
                    float[] appliedMotion = getQuaternionFormula().applyPositionalRotation(new float[] { (float)motions[0], (float)motions[1], (float)motions[2] });
                    float[] appliedRotation = getQuaternionFormula().applyRotationalRotation(new float[] { ent.rotationYaw, ent.rotationPitch, ent.getEntityWorld().isRemote ? getRoll(ent) : 0F });

                    AxisAlignedBB pairTeleportPlane = pair.getTeleportPlane(offset);

                    double destX = (pairTeleportPlane.maxX + pairTeleportPlane.minX) / 2D;
                    double destY = (pairTeleportPlane.maxY + pairTeleportPlane.minY) / 2D;
                    double destZ = (pairTeleportPlane.maxZ + pairTeleportPlane.minZ) / 2D;

                    EntityTransformationStack ets = new EntityTransformationStack(ent);
                    ets.translate(destX - ent.posX + appliedOffset[0], destY - (ent.posY + ent.getEyeHeight()) + appliedOffset[1], destZ - ent.posZ + appliedOffset[2]); //go to the centre of the dest portal and offset with the fields
                    ets.rotate(appliedRotation[0], appliedRotation[1], appliedRotation[2]);

                    ent.setPosition(ent.posX, ent.posY, ent.posZ);
                    double maxWidthHeight = Math.max(ent.width, ent.height);
                    EntityHelper.putEntityWithinAABB(ent, pair.scanRange.expand(pair.getFaceOn().getXOffset() * -maxWidthHeight, pair.getFaceOn().getYOffset() * -maxWidthHeight, pair.getFaceOn().getZOffset() * -maxWidthHeight));

                    ent.motionX = appliedMotion[0];
                    ent.motionY = appliedMotion[1];
                    ent.motionZ = appliedMotion[2];

                    //no going faster than 1 block a tick
                    if(Math.abs(ent.motionX) > 0.99D)
                    {
                        ent.motionX /= Math.abs(ent.motionX) + 0.001D;
                    }
                    if(Math.abs(ent.motionY) > 0.99D)
                    {
                        ent.motionY /= Math.abs(ent.motionY) + 0.001D;
                    }
                    if(Math.abs(ent.motionZ) > 0.99D)
                    {
                        ent.motionZ /= Math.abs(ent.motionZ) + 0.001D;
                    }
                    ent.fallDistance = 0.1F * ((float)ent.motionY / -0.1F * (float)ent.motionY / -0.1F);
                    ent.setPosition(ent.posX, ent.posY, ent.posZ);

                    //transfer over this entity to the other portal.
                    pair.teleportCooldown.put(ent, 3);
                    pair.lastScanEntities.add(ent);
                    if(pair.isAgainstWall())
                    {
                        WorldPortals.eventHandler.addMonitoredEntity(ent, pair);
                    }
                    teleportCooldown.put(ent, 3);
                    lastScanEntities.remove(ent);
                    //                    if(isAgainstWall()) //now removed by the teleport cooldown
                    //                    {
                    //                        WorldPortals.eventHandler.removeMonitoredEntity(ent, this);
                    //                    }

                    handleSpecialEntities(ent);

                    if(ent.getEntityWorld().isRemote)
                    {
                        handleClientEntityTeleport(ent, appliedRotation);
                    }
                    else
                    {
                        WorldPortals.channel.sendToAllAround(new PacketEntityLocation(ent), new NetworkRegistry.TargetPoint(ent.dimension, ent.posX, ent.posY, ent.posZ, 256D));
                    }
                }
            }
        }

        if(world.isRemote)
        {
            handleClient();
        }

        if(isAgainstWall())
        {
            lastScanEntities.removeAll(entitiesInRange); // now contains entities that are out of the range. Remove this from the tracking.
            for(Entity ent : lastScanEntities)
            {
                if(!teleportCooldown.containsKey(ent))
                {
                    WorldPortals.eventHandler.removeMonitoredEntity(ent, this);
                }
            }

            lastScanEntities = entitiesInRange;
        }
    }

    @SideOnly(Side.CLIENT)
    public float getRoll(Entity ent)
    {
        if(ent == Minecraft.getMinecraft().getRenderViewEntity())
        {
            return WorldPortals.eventHandlerClient.cameraRoll;
        }
        return 0F;
    }

    public boolean canEntityTeleport(Entity ent)
    {
        return true;
    }

    public void handleSpecialEntities(Entity ent)
    {
        if(ent instanceof EntityBlock)
        {
            ((EntityBlock)ent).timeExisting = 2;
        }
        else if(ent instanceof EntityFallingBlock)
        {
            ((EntityFallingBlock)ent).fallTime = 2;
        }
        else if(ent instanceof EntityFireball)
        {
            EntityFireball fireball = (EntityFireball)ent;
            float[] appliedAcceleration = getQuaternionFormula().applyPositionalRotation(new float[] { (float)fireball.accelerationX, (float)fireball.accelerationY, (float)fireball.accelerationZ });
            fireball.accelerationX = appliedAcceleration[0];
            fireball.accelerationY = appliedAcceleration[1];
            fireball.accelerationZ = appliedAcceleration[2];
        }
        else if(ent instanceof EntityArrow)
        {
            ((EntityArrow)ent).inGround = false;
        }
    }

    @SideOnly(Side.CLIENT)
    public void handleClient()
    {
        Minecraft mc = Minecraft.getMinecraft();

        //TODO a config for this?
        EnumFacing faceOn = getFaceOn();
        for (int i = 0; i < 4; ++i)
        {
            for (int j = 0; j < 2; ++j)
            {
                for(Particle particle : mc.effectRenderer.fxLayers[i][j])
                {
                    Vec3d particlePos = new Vec3d(particle.prevPosX, particle.prevPosY, particle.prevPosZ); //motion isn't accessible.
                    Vec3d newParticlePos = new Vec3d(particle.posX, particle.posY, particle.posZ);

                    float offset = (float)Math.abs((particle.prevPosX - particle.posX) * faceOn.getXOffset() * 1.5D + (particle.prevPosY - particle.posY) * faceOn.getYOffset() * 1.5D + (particle.prevPosZ - particle.posZ) * faceOn.getZOffset() * 1.5D);
                    boolean isRain = particle instanceof ParticleRain && faceOn == EnumFacing.UP && scanRange.contains(particlePos);
                    if(isRain || !portalInsides.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset).intersects(particle.getBoundingBox()) && portalInsides.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset).intersects(particle.getBoundingBox().offset(particle.motionX, particle.motionY, particle.motionZ)))
                    {
                        AxisAlignedBB teleportPlane = getTeleportPlane(offset);

                        double centerX = (teleportPlane.maxX + teleportPlane.minX) / 2D;
                        double centerY = (teleportPlane.maxY + teleportPlane.minY) / 2D;
                        double centerZ = (teleportPlane.maxZ + teleportPlane.minZ) / 2D;

                        if(pair != null)
                        {
                            float[] appliedOffset = getQuaternionFormula().applyPositionalRotation(new float[] { (float)(newParticlePos.x - centerX), (float)(newParticlePos.y - centerY), (float)(newParticlePos.z - centerZ) });
                            float[] appliedMotion = getQuaternionFormula().applyPositionalRotation(new float[] { (float)(newParticlePos.x - particlePos.x), (float)(newParticlePos.y - particlePos.y), (float)(newParticlePos.z - particlePos.z) });

                            AxisAlignedBB pairTeleportPlane = pair.getTeleportPlane(offset);

                            double destX = (pairTeleportPlane.maxX + pairTeleportPlane.minX) / 2D;
                            double destY = (pairTeleportPlane.maxY + pairTeleportPlane.minY) / 2D;
                            double destZ = (pairTeleportPlane.maxZ + pairTeleportPlane.minZ) / 2D;

                            double x = destX - particle.posX + appliedOffset[0];
                            double y = destY - particle.posY + appliedOffset[1];
                            double z = destZ - particle.posZ + appliedOffset[2];
                            particle.posX += x;
                            particle.posY += y;
                            particle.posZ += z;
                            particle.prevPosX += x;
                            particle.prevPosY += y;
                            particle.prevPosZ += z;
                            particle.setPosition(particle.posX, particle.posY, particle.posZ);
                            particle.motionX = appliedMotion[0];
                            particle.motionY = appliedMotion[1];
                            particle.motionZ = appliedMotion[2];
                            if(isRain)
                            {
                                particle.motionX *= 5D;
                                particle.motionY *= 5D;
                                particle.motionZ *= 5D;
                            }
                        }
                    }
                }
            }
        }
    }

    public void terminate()
    {
        if(isAgainstWall())
        {
            for(Entity ent : lastScanEntities)
            {
                if(ent.getEntityBoundingBox().intersects(portalInsides))
                {
                    EnumFacing faceOn = getFaceOn();
                    EntityHelper.putEntityWithinAABB(ent, flatPlane.offset(faceOn.getXOffset() * 0.5D, faceOn.getYOffset() * 0.5D, faceOn.getZOffset() * 0.5D));
                    ent.setPosition(ent.posX, ent.posY, ent.posZ);
                }
                WorldPortals.eventHandler.removeMonitoredEntity(ent, this);
            }
        }
        if(hasPair())
        {
            pair.setPair(null);
            setPair(null);
        }
    }

    public boolean isValid()
    {
        return !firstUpdate;
    }

    public boolean isFirstUpdate()
    {
        return firstUpdate;
    }

    public void forceFirstUpdate()
    {
        firstUpdate = true;
    }

    //Only for WorldPortals that can teleport
    public boolean isAgainstWall() //you have world, pos, faceOn, etc all to check. This is to remove the collision behind the portal.
    {
        return false;
    }

    private AxisAlignedBB createPlaneAround(double size)
    {
        return createPlaneAround(getPosition(), size);
    }

    private AxisAlignedBB createPlaneAround(Vec3d pos, double size)
    {
        double halfW = width / 2D;
        double halfH = height / 2D;

        AxisAlignedBB plane = new AxisAlignedBB(pos.x - halfW, pos.y - halfH, pos.z - size, pos.x + halfW, pos.y + halfH, pos.z + size);
        EnumFacing faceOn = getFaceOn();
        if(faceOn.getAxis() == EnumFacing.Axis.Y)
        {
            plane = EntityHelper.rotateAABB(EnumFacing.Axis.X, plane, faceOn == EnumFacing.UP ? -90F : 90F, pos.x, pos.y, pos.z);
        }
        plane = EntityHelper.rotateAABB(EnumFacing.Axis.Y, plane, faceOn.getAxis() == EnumFacing.Axis.X ? 90F : faceOn.getAxis() == EnumFacing.Axis.Y && getUpDir().getAxis() == EnumFacing.Axis.X ? 90F : 0F, pos.x, pos.y, pos.z).offset(faceOn.getXOffset() * getPlaneOffset(), faceOn.getYOffset() * getPlaneOffset(), faceOn.getZOffset() * getPlaneOffset());
        return plane;
    }

    public AxisAlignedBB getCollisionRemovalAabbForEntity(Entity ent)
    {
        double max = Math.max(Math.max(ent.width, ent.height) + Math.sqrt(ent.motionX * ent.motionX + ent.motionY * ent.motionY + ent.motionZ * ent.motionZ), 1D);
        EnumFacing faceOn = getFaceOn();
        return flatPlane.expand(faceOn.getXOffset() * -max, faceOn.getYOffset() * -max, faceOn.getZOffset() * -max);
    }

    public AxisAlignedBB getPortalInsides(Entity ent)
    {
        if(isAgainstWall() && ent instanceof EntityPlayer)
        {
            EnumFacing faceOn = getFaceOn();
            float offset = Math.min(0.05F, (float)Math.abs((flatPlane.minX - ent.posX) * faceOn.getXOffset() + (flatPlane.minY - ent.posY) * faceOn.getYOffset() + (flatPlane.minZ - ent.posZ) * faceOn.getZOffset()));
            return portalInsides.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset);
        }
        return portalInsides;
    }

    public AxisAlignedBB getPlane()
    {
        return plane;
    }

    private void setupAABBs()
    {
        EnumFacing faceOn = getFaceOn();
        plane = createPlaneAround(0.0125D);
        flatPlane = createPlaneAround(0);
        scanRange = flatPlane.expand(faceOn.getXOffset() * getScanDistance(), faceOn.getYOffset() * getScanDistance(), faceOn.getZOffset() * getScanDistance());
        portalInsides = flatPlane.expand(faceOn.getXOffset() * -100D, faceOn.getYOffset() * -100D, faceOn.getZOffset() * -100D);
    }

    public AxisAlignedBB getFlatPlane()
    {
        return flatPlane;
    }

    public AxisAlignedBB getTeleportPlane(float offset)
    {
        if(offset != 0F)
        {
            EnumFacing faceOn = getFaceOn();
            return flatPlane.offset(faceOn.getXOffset() * offset, faceOn.getYOffset() * offset, faceOn.getZOffset() * offset);
        }
        return flatPlane;
    }

    public boolean getCullRender()
    {
        return !renderAll; //cullRender;
    }

    public void setCullRender(boolean flag)
    {
        //cullRender = flag;
        renderAll = !flag;
    }

    public boolean canTeleportEntities()
    {
        return true;
    }

    public HashSet<AxisAlignedBB> getCollisionBoundaries()
    {
        if(collisions == null)
        {
            collisions = new HashSet<>(4);

            if(canCollideWithBorders())
            {
                double size = 0.0125D;
                AxisAlignedBB plane = flatPlane;

                if(plane.maxX - plane.minX > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.maxX, plane.minY, plane.minZ, plane.maxX + size, plane.maxY, plane.maxZ));
                    collisions.add(new AxisAlignedBB(plane.minX - size, plane.minY, plane.minZ, plane.minX, plane.maxY, plane.maxZ));
                }
                if(plane.maxY - plane.minY > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.minX, plane.maxY, plane.minZ, plane.maxX, plane.maxY + size, plane.maxZ));
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY - size, plane.minZ, plane.maxX, plane.minY, plane.maxZ));
                }
                if(plane.maxZ - plane.minZ > size * 3D)
                {
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.maxZ, plane.maxX, plane.maxY, plane.maxZ + size));
                    collisions.add(new AxisAlignedBB(plane.minX, plane.minY, plane.minZ - size, plane.maxX, plane.maxY, plane.minZ));
                }
            }
        }
        return collisions;
    }

    public boolean hasPair()
    {
        return pair != null && pair.position.y > 0D;
    }

    public void setPair(WorldPortal portal)
    {
        if(pair != portal)
        {
            pair = portal;
            if(pair != null)
            {
                quaternionFormula = QuaternionFormula.createFromPlanes(getFaceOn(), getUpDir(), pair.getFaceOn(), pair.getUpDir());
            }
        }
    }

    public WorldPortal getPair()
    {
        return pair;
    }

    public void setPosition(Vec3d v)
    {
        this.position = v;
        this.posBlock = new BlockPos(v);
        setupAABBs();
    }

    public Vec3d getPosition() //position of the world portal, pre-offset
    {
        return position;
    }

    public QuaternionFormula getQuaternionFormula()
    {
        return pair != null ? quaternionFormula : QuaternionFormula.NO_ROTATION;
    }

    public NBTTagCompound write(NBTTagCompound tag)
    {
        return writePair(writeSelf(tag));
    }

    public NBTTagCompound writeSelf(NBTTagCompound tag)
    {
        tag.setFloat("width", width);
        tag.setFloat("height", height);

        tag.setInteger("faceOn", faceOn.getIndex());
        tag.setInteger("up", upDir.getIndex());

        tag.setDouble("posX", position.x);
        tag.setDouble("posY", position.y);
        tag.setDouble("posZ", position.z);

        tag.setInteger("time", time);

        return tag;
    }

    public NBTTagCompound writePair(NBTTagCompound tag)
    {
        if(hasPair())
        {
            tag.setTag("pair", pair.writeSelf(new NBTTagCompound()));
        }
        return tag;
    }

    public void read(NBTTagCompound tag)
    {
        readSelf(tag);
        readPair(tag);
    }

    public void readSelf(NBTTagCompound tag)
    {
        setSize(tag.getFloat("width"), tag.getFloat("height"));
        setFace(EnumFacing.byIndex(tag.getInteger("faceOn")), EnumFacing.byIndex(tag.getInteger("up")));
        setPosition(new Vec3d(tag.getDouble("posX"), tag.getDouble("posY"), tag.getDouble("posZ")));

        time = tag.getInteger("time");

        firstUpdate = true;
    }

    public void readPair(NBTTagCompound tag)
    {
        if(tag.hasKey("pair"))
        {
            setPair(createFakeInstance(tag.getCompoundTag("pair")));
        }
    }

    public abstract <T extends WorldPortal> T createFakeInstance(NBTTagCompound tag);

    @SideOnly(Side.CLIENT)
    public void handleClientEntityTeleport(Entity ent, float[] rotations)
    {
        if(ent == Minecraft.getMinecraft().player)
        {
            WorldPortals.eventHandlerClient.prevCameraRoll = WorldPortals.eventHandlerClient.cameraRoll = rotations[2];
            WorldPortals.channel.sendToServer(new PacketEntityLocation(ent));
        }
    }

    @SideOnly(Side.CLIENT)
    public boolean shouldRenderFront(Entity viewer, float partialTicks) //TODO THIS
    {
        Vec3d position = RendererHelper.getCameraPosition(viewer, partialTicks);
        return !getCullRender() || faceOn.getXOffset() < 0 && position.x < flatPlane.minX || faceOn.getXOffset() > 0 && position.x > flatPlane.minX ||
                faceOn.getYOffset() < 0 && position.y < flatPlane.minY || faceOn.getYOffset() > 0 && position.y > flatPlane.minY ||
                faceOn.getZOffset() < 0 && position.z < flatPlane.minZ || faceOn.getZOffset() > 0 && position.z > flatPlane.minZ;
    }

    @SideOnly(Side.CLIENT)
    public int getRenderDistanceChunks()
    {
        return iChunUtil.config.renderDistanceChunks == 0 ? Minecraft.getMinecraft().gameSettings.renderDistanceChunks : iChunUtil.config.renderDistanceChunks;
    }
}
