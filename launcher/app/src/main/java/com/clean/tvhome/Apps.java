package com.clean.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads the TV apps (LEANBACK_LAUNCHER activities) with their banners and line glyphs. */
final class Apps {

    static final class Entry {
        String pkg;
        ComponentName component;
        String label;
        Drawable banner;          // null when the app ships no banner
        LineIcons.Glyph glyph;    // null when neither Lawnicons nor the app's initial gives one
    }

    private Apps() {}

    static List<Entry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> hidden = new HashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.hidden_apps)));
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);

        Map<String, Entry> byPackage = new LinkedHashMap<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(context.getPackageName())
                    || hidden.contains(ai.packageName)
                    || byPackage.containsKey(ai.packageName)) continue;
            Entry e = new Entry();
            e.pkg = ai.packageName;
            e.component = new ComponentName(ai.packageName, ai.name);
            e.label = ri.loadLabel(pm).toString();
            e.banner = ai.loadBanner(pm);
            if (e.banner == null) e.banner = ai.applicationInfo.loadBanner(pm);
            byPackage.put(e.pkg, e);
        }
        List<Entry> apps = new ArrayList<>(byPackage.values());
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        LineIcons.attach(context, apps);
        return apps;
    }
}
