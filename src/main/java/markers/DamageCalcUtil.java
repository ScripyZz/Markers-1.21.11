package markers;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
public final class DamageCalcUtil {
    private DamageCalcUtil() {
    }

    public static float applyArmorAndProtection(float damage, float armorPoints, float toughness, int epf, int breachLevel) {
        float protectionReduction;
        float armorReduction = DamageCalcUtil.getArmorReductionFraction(damage, armorPoints, toughness);
        float totalReduction = armorReduction + (protectionReduction = DamageCalcUtil.getProtectionReductionFraction(epf, breachLevel));
        if (totalReduction > 0.8f) {
            totalReduction = 0.8f;
        }
        if (totalReduction < 0.0f) {
            totalReduction = 0.0f;
        }
        return damage * (1.0f - totalReduction);
    }

    public static float getArmorReductionFraction(float damage, float armorPoints, float toughness) {
        float f = Math.min(20.0f, Math.max(armorPoints / 5.0f, armorPoints - damage / (2.0f + toughness / 4.0f)));
        return f / 25.0f;
    }

    public static float getProtectionReductionFraction(int epf, int breachLevel) {
        int capped = Math.min(epf, 20);
        float percent = (float)capped * 0.04f;
        if ((percent -= (float)breachLevel * 0.15f) < 0.0f) {
            percent = 0.0f;
        }
        return percent;
    }
}
