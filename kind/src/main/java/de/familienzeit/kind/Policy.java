package de.familienzeit.kind;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserManager;
import android.provider.Telephony;
import android.telecom.TelecomManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Alles, was die App als Geräteeigentümer auf dem Handy durchsetzt. */
final class Policy {
    private Policy() {}

    /** Sperren, die immer gelten, solange die App aktiv ist. */
    static final String[] BASE = {
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_LOCATION,
    };
    /** Neue Sperre ab Android 15: kein "Privater Bereich". */
    static final String NO_PRIVATE_SPACE = "no_add_private_profile";

    static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    static void applyBase(Context c, boolean allowDebugging) {
        if (!isOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        for (String r : BASE) safeAdd(d, a, r);
        if (Build.VERSION.SDK_INT >= 35) safeAdd(d, a, NO_PRIVATE_SPACE);
        if (allowDebugging) d.clearUserRestriction(a, UserManager.DISALLOW_DEBUGGING_FEATURES);
        else safeAdd(d, a, UserManager.DISALLOW_DEBUGGING_FEATURES);

        d.setUninstallBlocked(a, c.getPackageName(), true);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                d.setUserControlDisabledPackages(a, Collections.singletonList(c.getPackageName()));
                d.setAutoTimeEnabled(a, true);
                d.setAutoTimeZoneEnabled(a, true);
            } catch (Exception ignored) {
            }
        }
        List<String> perms = new ArrayList<>(Arrays.asList(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS);
        for (String perm : perms) {
            try {
                d.setPermissionGrantState(a, c.getPackageName(), perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            } catch (Exception ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                d.setLocationEnabled(a, true);
            } catch (Exception ignored) {
            }
        }
    }

    private static void safeAdd(DevicePolicyManager d, ComponentName a, String r) {
        try {
            d.addUserRestriction(a, r);
        } catch (Exception ignored) {
        }
    }

    /** Vor einem Update kurz lockern, damit die Installation sicher durchgeht. */
    static void allowSelfUpdate(Context c) {
        if (!isOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY);
    }

    /** Hebt die komplette Kontrolle auf. Danach kann die App normal deinstalliert werden. */
    static void release(Context c) {
        if (!isOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        Set<String> sus = Store.strings(c, "suspended");
        if (!sus.isEmpty()) {
            try {
                d.setPackagesSuspended(a, sus.toArray(new String[0]), false);
            } catch (Exception ignored) {
            }
        }
        Store.putStrings(c, "suspended", new HashSet<>());
        setInternetBlocked(c, false, new HashSet<>());
        List<String> all = new ArrayList<>(Arrays.asList(BASE));
        all.add(UserManager.DISALLOW_DEBUGGING_FEATURES);
        all.add(NO_PRIVATE_SPACE);
        for (String r : all) {
            try {
                d.clearUserRestriction(a, r);
            } catch (Exception ignored) {
            }
        }
        d.setUninstallBlocked(a, c.getPackageName(), false);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                d.setUserControlDisabledPackages(a, new ArrayList<>());
            } catch (Exception ignored) {
            }
        }
        Store.p(c).edit().clear().apply();
        c.stopService(new Intent(c, GuardService.class));
        d.clearDeviceOwnerApp(c.getPackageName());
    }

    /** Alle Apps mit Symbol in der App-Übersicht. */
    static List<ResolveInfo> launchable(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return c.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL);
    }

    /** Apps, die nie gesperrt werden: Telefon, SMS, Startbildschirm, Einstellungen, Uhr, diese App. */
    static Set<String> essentials(Context c) {
        Set<String> s = new HashSet<>();
        s.add(c.getPackageName());
        s.add("com.android.systemui");
        s.add("com.android.settings");
        s.add("com.google.android.deskclock");
        s.add("com.android.emergency");
        s.add("com.google.android.apps.safetyhub");
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm != null && tm.getDefaultDialerPackage() != null) s.add(tm.getDefaultDialerPackage());
        } catch (Exception ignored) {
        }
        String sms = Telephony.Sms.getDefaultSmsPackage(c);
        if (sms != null) s.add(sms);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            s.add(ri.activityInfo.packageName);
        }
        return s;
    }

    /** Sperrt genau die übergebenen Apps (und entsperrt alle anderen, die wir gesperrt hatten). */
    static void applySuspension(Context c, Set<String> target) {
        if (!isOwner(c)) return;
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        Set<String> current = Store.strings(c, "suspended");

        Set<String> toFree = new HashSet<>(current);
        toFree.removeAll(target);
        if (!toFree.isEmpty()) {
            try {
                d.setPackagesSuspended(a, toFree.toArray(new String[0]), false);
            } catch (Exception ignored) {
            }
        }
        Set<String> toLock = new HashSet<>(target);
        toLock.removeAll(current);
        Set<String> result = new HashSet<>(current);
        result.removeAll(toFree);
        if (!toLock.isEmpty()) {
            String[] failed = new String[0];
            try {
                failed = d.setPackagesSuspended(a, toLock.toArray(new String[0]), true);
            } catch (Exception ignored) {
            }
            result.addAll(toLock);
            if (failed != null) result.removeAll(Arrays.asList(failed));
        }
        Store.putStrings(c, "suspended", result);
    }

    /** Wiederholt die Sperre (z. B. nach Neuinstallation einer App). */
    static void resuspend(Context c, String pkg) {
        Set<String> wanted = Store.strings(c, "wantedSuspended");
        if (!wanted.contains(pkg) || !isOwner(c)) return;
        try {
            dpm(c).setPackagesSuspended(admin(c), new String[]{pkg}, true);
        } catch (Exception ignored) {
        }
        Set<String> cur = Store.strings(c, "suspended");
        cur.add(pkg);
        Store.putStrings(c, "suspended", cur);
    }

    /** Internet sperren über ein nicht abschaltbares "Always-on"-VPN, das alles verwirft. */
    static void setInternetBlocked(Context c, boolean blocked, Set<String> allowed) {
        if (!isOwner(c)) return;
        boolean was = Store.netBlocked(c);
        Set<String> oldAllowed = Store.strings(c, "netAllowed");
        Store.p(c).edit().putBoolean("netBlocked", blocked).apply();
        Store.putStrings(c, "netAllowed", allowed);
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        try {
            if (blocked) {
                if (was && oldAllowed.equals(allowed)) return;
                Set<String> bypass = new HashSet<>();
                bypass.add(c.getPackageName());
                PackageManager pm = c.getPackageManager();
                for (String p : allowed) {
                    try {
                        pm.getPackageInfo(p, 0);
                        bypass.add(p);
                    } catch (PackageManager.NameNotFoundException ignored) {
                    }
                }
                d.setAlwaysOnVpnPackage(a, c.getPackageName(), true, bypass);
                c.startService(new Intent(c, BlockVpnService.class));
            } else {
                if (!was) return;
                d.setAlwaysOnVpnPackage(a, null, false);
                c.startService(new Intent(c, BlockVpnService.class).setAction(BlockVpnService.STOP));
            }
        } catch (Exception ignored) {
        }
    }
}
