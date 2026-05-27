package markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

@Environment(value=EnvType.CLIENT)
public class DamageDisplayMod
implements ClientModInitializer {
    private static final String ENABLED_KEY = "damage_display_enabled";
    private static final List<DamageEntry> DAMAGE_ENTRIES = new ArrayList<DamageEntry>();
    private static final Map<UUID, Float> ENTITY_LAST_HEALTH = new HashMap<UUID, Float>();
    private static final Random RANDOM = new Random();
    private static final int HEALTH_CHECK_INTERVAL = 1;
    private static final long HIT_DETECTION_WINDOW_MS = 250L;
    private static final long FORGET_ENTITY_AFTER_MS = 5000L;
    private static final long DISPLAY_DURATION_MS = 1500L;
    private static final float TEXT_SCALE = 0.035f;
    private static final float HORIZONTAL_SPREAD = 0.6f;
    private static final float VERTICAL_SPREAD = 0.6f;
    private static final float DEPTH_SPREAD = 0.6f;
    private static final float FRONT_OFFSET = 1.2f;
    private static final float SCALE_FACTOR_PER_ENTRY = 0.5f;
    private static boolean enabled = true;
    private static KeyBinding toggleKey;
    private static int tickCounter;
    private static UUID lastHitEntityId;
    private static long lastHitTime;
    private static boolean wasCriticalHit;

    @Override
    public void onInitializeClient() {
        enabled = ModConfig.get().damageDisplayEnabled;
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.damagedisplay.toggle",
            InputUtil.Type.KEYSYM,
            -1,
            KeyBinding.Category.MISC
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                DamageDisplayMod.setEnabled(!enabled);
                if (client.player != null) {
                    client.player.sendMessage(Text.literal("Damage Indicators: " + (enabled ? "ON" : "OFF")), true);
                }
            }
            if (enabled) {
                this.checkAttackAndEntityHealth(client);
            }
        });
        WorldRenderEvents.AFTER_ENTITIES.register(this::renderDamageNumbers);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ModConfig.get().damageDisplayEnabled = value;
        ModConfig.save();
        if (!value) {
            DAMAGE_ENTRIES.clear();
        }
    }

    private void checkAttackAndEntityHealth(MinecraftClient client) {
        LivingEntity livingEntity;
        EntityHitResult hitResult;
        Entity hitEntity;
        HitResult crosshairTarget;
        if (client.player == null || client.world == null) {
            return;
        }
        ++tickCounter;
        boolean attacking = client.options.attackKey.isPressed();
        if (attacking && (crosshairTarget = client.crosshairTarget) instanceof EntityHitResult) {
            hitResult = (EntityHitResult) crosshairTarget;
            hitEntity = hitResult.getEntity();
            if (hitEntity instanceof LivingEntity && hitEntity != client.player) {
                livingEntity = (LivingEntity) hitEntity;
                lastHitEntityId = livingEntity.getUuid();
                lastHitTime = System.currentTimeMillis();
                wasCriticalHit = client.player.fallDistance > 0.0f 
                    && !client.player.isOnGround() 
                    && !client.player.isClimbing() 
                    && !client.player.isTouchingWater() 
                    && !client.player.hasStatusEffect(StatusEffects.BLINDNESS) 
                    && !client.player.hasVehicle();
                ENTITY_LAST_HEALTH.putIfAbsent(lastHitEntityId, livingEntity.getHealth());
            }
        }
        if (tickCounter % 1 != 0) {
            return;
        }
        ArrayList<UUID> keysToRemove = new ArrayList<UUID>();
        for (Map.Entry<UUID, Float> entry : ENTITY_LAST_HEALTH.entrySet()) {
            UUID entityId = entry.getKey();
            float lastKnownHealth = entry.getValue();
            LivingEntity livingEntity2 = client.world.getEntitiesByClass(
                LivingEntity.class, 
                client.player.getBoundingBox().expand(20.0), 
                entity -> entity.getUuid().equals(entityId)
            ).stream().findFirst().orElse(null);
            
            if (livingEntity2 == null) {
                if (System.currentTimeMillis() - lastHitTime <= 5000L || entityId.equals(lastHitEntityId)) {
                    continue;
                }
                keysToRemove.add(entityId);
                continue;
            }
            float currentHealth = livingEntity2.getHealth();
            long currentTime = System.currentTimeMillis();
            boolean isRecentHit = entityId.equals(lastHitEntityId) && currentTime - lastHitTime < 250L;
            boolean isNearby = livingEntity2.squaredDistanceTo(client.player) < 36.0;
            boolean isPlayer = livingEntity2 instanceof PlayerEntity;
            
            if (currentHealth < lastKnownHealth) {
                float damageDealt = lastKnownHealth - currentHealth;
                if (damageDealt > 0.0f && (isRecentHit || isNearby) && livingEntity2 != client.player) {
                    DAMAGE_ENTRIES.add(new DamageEntry(
                        livingEntity2, 
                        damageDealt, 
                        new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()), 
                        isRecentHit && wasCriticalHit, 
                        false, 
                        isPlayer
                    ));
                }
                ENTITY_LAST_HEALTH.put(entityId, currentHealth);
            } else if (currentHealth > lastKnownHealth) {
                float healingAmount = currentHealth - lastKnownHealth;
                if (healingAmount > 0.0f && (isRecentHit || isNearby) && livingEntity2 != client.player) {
                    DAMAGE_ENTRIES.add(new DamageEntry(
                        livingEntity2, 
                        healingAmount, 
                        new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()), 
                        false, 
                        true, 
                        isPlayer
                    ));
                }
                ENTITY_LAST_HEALTH.put(entityId, currentHealth);
            }
            if (System.currentTimeMillis() - lastHitTime <= 5000L || entityId.equals(lastHitEntityId)) {
                continue;
            }
            keysToRemove.add(entityId);
        }
        for (UUID id : keysToRemove) {
            ENTITY_LAST_HEALTH.remove(id);
        }
        client.world.getEntitiesByClass(
            LivingEntity.class, 
            client.player.getBoundingBox().expand(20.0), 
            entity -> entity != client.player && !ENTITY_LAST_HEALTH.containsKey(entity.getUuid())
        ).forEach(entity -> ENTITY_LAST_HEALTH.put(entity.getUuid(), entity.getHealth()));
        wasCriticalHit = false;
    }

    private void renderDamageNumbers(WorldRenderContext context) {
        if (!enabled) {
            return;
        }
        DAMAGE_ENTRIES.removeIf(DamageEntry::isExpired);
        for (DamageEntry entry : DAMAGE_ENTRIES) {
            this.renderFloatingDamage(context, entry);
        }
    }

    private void renderFloatingDamage(WorldRenderContext context, DamageEntry entry) {
        float animationProgress;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null || context.matrices() == null || context.consumers() == null) {
            return;
        }
        MatrixStack matrices = context.matrices();
        Camera camera = context.gameRenderer().getCamera();
        if (camera == null) {
            return;
        }
        float amount = entry.amount();
        if (amount <= 0.0f) {
            return;
        }
        matrices.push();
        Vec3d camPos = camera.getCameraPos();
        Vec3d entityPos = entry.getCurrentEntityPos();
        double distanceToEntity = entityPos.distanceTo(camPos);
        float elapsedTimeSeconds = (float)(System.currentTimeMillis() - entry.createdTime()) / 1000.0f;
        float verticalMovement = this.calculateVerticalMovement(elapsedTimeSeconds);
        Vec3d cameraForward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d offset = new Vec3d(entry.horizontalOffset() * 0.6f, entry.verticalOffset() * 0.6f, entry.depthOffset() * 0.6f);
        float dynamicFrontOffset = 1.2f * (float)Math.max(1.0, distanceToEntity * 0.3);
        Vec3d textPos = entityPos.add(
            cameraForward.x * (double)dynamicFrontOffset + offset.x, 
            (double)(entry.entityHeight() * 0.5f) + offset.y + (double)verticalMovement, 
            cameraForward.z * (double)dynamicFrontOffset + offset.z
        );
        matrices.translate(textPos.x - camPos.x, textPos.y - camPos.y, textPos.z - camPos.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-camera.getYaw()));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        float scale = 0.035f * (float)(1.0 + 1.0 / (distanceToEntity + 1.0));
        scale = Math.min(scale, 0.065f);
        List<DamageEntry> sameEntityEntries = DAMAGE_ENTRIES.stream().filter(other -> other.entityUUID().equals(entry.entityUUID()) && !other.isExpired()).sorted((a, b) -> Long.compare(b.createdTime(), a.createdTime())).collect(Collectors.toList());
        int entryIndex = sameEntityEntries.indexOf(entry);
        if (entryIndex >= 0) {
            scale *= (float)Math.pow(0.5, entryIndex);
        }
        if ((animationProgress = elapsedTimeSeconds / 1.5f) < 0.2f) {
            scale *= 0.8f + animationProgress;
        } else if (animationProgress > 0.7f) {
            scale *= 1.0f - (animationProgress - 0.7f) * 0.7f;
        }
        matrices.scale(-scale, -scale, scale);
        TextRenderer textRenderer = client.textRenderer;
        String text = entry.isHealing() ? String.format(Locale.ROOT, "+%d \u2764", Math.round(amount)) : String.format(Locale.ROOT, "-%d \u2764", Math.round(amount));
        int colorWithAlpha = this.getTextColor(entry, animationProgress);
        MutableText class_52502 = Text.literal(text);
        float f = (float)(-textRenderer.getWidth(text)) / 2.0f;
        textRenderer.draw((Text)class_52502, f, (float)(-9) / 2.0f, colorWithAlpha, true, matrices.peek().getPositionMatrix(), context.consumers(), TextRenderer.TextLayerType.SEE_THROUGH, 0, 0xF000F0);
        matrices.pop();
    }

    private float calculateVerticalMovement(float elapsedTime) {
        if (elapsedTime < 0.2f) {
            return elapsedTime * 2.5f;
        }
        return 0.5f - (elapsedTime - 0.2f) * 0.4f;
    }

    private int getTextColor(DamageEntry entry, float progress) {
        int color = entry.isHealing() ? -16711936 : (entry.isPlayer() ? -65536 : -1);
        int alpha = (int)(255.0f * (1.0f - Math.max(0.0f, (progress - 0.7f) / 0.3f)));
        return color & 0xFFFFFF | alpha << 24;
    }

    static {
        tickCounter = 0;
        lastHitEntityId = null;
        lastHitTime = 0L;
        wasCriticalHit = false;
    }

    @Environment(value=EnvType.CLIENT)
    private record DamageEntry(UUID entityUUID, Vec3d attackPos, long createdTime, float amount, float entityHeight, float entityWidth, float horizontalOffset, float verticalOffset, float depthOffset, boolean isCritical, boolean isHealing, boolean isPlayer, Vec3d initialEntityPos) {
        private DamageEntry(LivingEntity entity, float amount, Vec3d attackPos, boolean isCritical, boolean isHealing, boolean isPlayer) {
            this(entity.getUuid(), attackPos, System.currentTimeMillis(), amount, entity.getHeight(), entity.getWidth(), RANDOM.nextFloat() * 2.0f - 1.0f, RANDOM.nextFloat() * 2.0f - 1.0f, RANDOM.nextFloat() * 2.0f - 1.0f, isCritical, isHealing, isPlayer, new Vec3d(entity.getX(), entity.getY(), entity.getZ()));
        }

        private boolean isExpired() {
            return System.currentTimeMillis() - this.createdTime > 1500L;
        }

        private Vec3d getCurrentEntityPos() {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) {
                return this.initialEntityPos;
            }
            LivingEntity entity = client.world.getEntitiesByClass(LivingEntity.class, new Box(this.initialEntityPos.x - 10.0, this.initialEntityPos.y - 10.0, this.initialEntityPos.z - 10.0, this.initialEntityPos.x + 10.0, this.initialEntityPos.y + 10.0, this.initialEntityPos.z + 10.0), livingEntity -> livingEntity.getUuid().equals(this.entityUUID)).stream().findFirst().orElse(null);
            return entity != null ? new Vec3d(entity.getX(), entity.getY(), entity.getZ()) : this.initialEntityPos;
        }
    }
}
