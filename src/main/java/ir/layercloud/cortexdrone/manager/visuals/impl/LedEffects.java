package ir.layercloud.cortexdrone.manager.visuals.impl;

import lombok.experimental.UtilityClass;

/**
 * Manager-owned LED color parser, HSV-to-RGB converter and procedural lighting
 * animation steps for OpenComputers Drones.
 * Supports: RAINBOW, DISCO, POLICE, PULSE/BREATHE, STROBE, FIRE, OCEAN, HEARTBEAT, MATRIX.
 */
@UtilityClass
public final class LedEffects {
    private static final int[] DISCO_PALETTE = new int[]{
            0xFF0055, 0x00FFCC, 0xFFEE00, 0xAA00FF, 0x00FF44, 0xFF6600, 0xFF00AA
    };

    public static int hsvToRgbInt(float hDeg, float s, float v) {
        float h = ((hDeg % 360.0f) + 360.0f) % 360.0f;
        float c = v * s;
        float x = c * (1.0f - Math.abs(((h / 60.0f) % 2.0f) - 1.0f));
        float m = v - c;
        float r1 = 0, g1 = 0, b1 = 0;
        if (h < 60) {
            r1 = c;
            g1 = x;
            b1 = 0;
        } else if (h < 120) {
            r1 = x;
            g1 = c;
            b1 = 0;
        } else if (h < 180) {
            r1 = 0;
            g1 = c;
            b1 = x;
        } else if (h < 240) {
            r1 = 0;
            g1 = x;
            b1 = c;
        } else if (h < 300) {
            r1 = x;
            g1 = 0;
            b1 = c;
        } else {
            r1 = c;
            g1 = 0;
            b1 = x;
        }
        int r = Math.min(255, Math.max(0, Math.round((r1 + m) * 255.0f)));
        int g = Math.min(255, Math.max(0, Math.round((g1 + m) * 255.0f)));
        int b = Math.min(255, Math.max(0, Math.round((b1 + m) * 255.0f)));
        return (r << 16) | (g << 8) | b;
    }

    public static Integer parseNamedOrHexColor(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;
        if (s.startsWith("#")) s = s.substring(1);
        else if (s.startsWith("0x")) s = s.substring(2);
        try {
            if (s.matches("^[0-9a-f]{3,6}$")) {
                return Integer.parseInt(s, 16) & 0xFFFFFF;
            }
        } catch (NumberFormatException ignored) {
        }
        if (s.contains("red") || s.contains("crimson")) return 0xFF1122;
        if (s.contains("orange") || s.contains("amber")) return 0xFF7700;
        if (s.contains("yellow") || s.contains("gold")) return 0xFFDD00;
        if (s.contains("lime") || s.contains("emerald") || s.contains("green")) return 0x00FF55;
        if (s.contains("cyan") || s.contains("aqua") || s.contains("teal") || s.contains("turquoise")) return 0x00FFFF;
        if (s.contains("blue") || s.contains("navy") || s.contains("cobalt")) return 0x0066FF;
        if (s.contains("purple") || s.contains("violet") || s.contains("indigo")) return 0x9922FF;
        if (s.contains("pink") || s.contains("magenta") || s.contains("rose") || s.contains("fuchsia")) return 0xFF22AA;
        if (s.contains("white") || s.contains("silver")) return 0xFFFFFF;
        if (s.contains("black") || s.contains("off") || s.contains("dark")) return 0x111111;
        return null;
    }

    public static boolean isAnimated(String raw) {
        return isEffectName(raw);
    }

    public static boolean isEffectName(String raw) {
        if (raw == null) return false;
        String u = raw.trim().toUpperCase();
        return u.equals("RAINBOW") || u.equals("DISCO") || u.equals("POLICE")
                || u.equals("PULSE") || u.equals("BREATHE") || u.equals("STROBE")
                || u.equals("FIRE") || u.equals("OCEAN") || u.equals("HEARTBEAT") || u.equals("MATRIX");
    }

    public static int resolveEffectiveLedColor(String ledEffect, int tick, Integer customLedColor, int fallbackColor) {
        String eff = ledEffect != null ? ledEffect.toUpperCase() : "NONE";
        switch (eff) {
            case "RAINBOW":
                return hsvToRgbInt((tick * 8.0f) % 360.0f, 1.0f, 1.0f);
            case "DISCO":
                return DISCO_PALETTE[(tick / 3) % DISCO_PALETTE.length];
            case "POLICE":
                return (tick % 8 < 4) ? 0xFF0000 : 0x0044FF;
            case "STROBE": {
                int base = customLedColor != null ? customLedColor : 0xFFFFFF;
                return (tick % 4 < 2) ? base : 0x111111;
            }
            case "FIRE": {
                float hue = 18.0f + (float) (Math.sin(tick * 0.45) * 14.0 + Math.cos(tick * 0.9) * 6.0);
                float val = 0.80f + (float) (0.20 * Math.sin(tick * 0.7));
                return hsvToRgbInt(Math.max(0.0f, hue), 1.0f, val);
            }
            case "OCEAN": {
                float hue = 195.0f + (float) (Math.sin(tick * 0.18) * 25.0);
                return hsvToRgbInt(hue, 0.90f, 0.95f);
            }
            case "HEARTBEAT": {
                int phase = tick % 20;
                float v = (phase == 0 || phase == 1 || phase == 4 || phase == 5) ? 1.0f : 0.22f;
                return hsvToRgbInt(352.0f, 0.95f, v);
            }
            case "MATRIX": {
                float v = 0.35f + 0.65f * (float) (0.5 + 0.5 * Math.sin(tick * 0.35));
                return hsvToRgbInt(125.0f, 0.95f, v);
            }
            case "PULSE":
            case "BREATHE": {
                int base = customLedColor != null ? customLedColor : fallbackColor;
                double factor = 0.25 + 0.75 * (0.5 + 0.5 * Math.sin(tick * 0.22));
                int r = (int) Math.round(((base >> 16) & 0xFF) * factor);
                int g = (int) Math.round(((base >> 8) & 0xFF) * factor);
                int b = (int) Math.round((base & 0xFF) * factor);
                return (r << 16) | (g << 8) | b;
            }
        }
        if (customLedColor != null) {
            return customLedColor;
        }
        return fallbackColor;
    }
}
