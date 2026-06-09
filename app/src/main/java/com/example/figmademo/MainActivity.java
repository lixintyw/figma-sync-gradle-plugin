package com.example.figmademo;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RecyclerView recycler = findViewById(R.id.recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        // Divider between items
        DividerItemDecoration divider = new DividerItemDecoration(this,
            DividerItemDecoration.VERTICAL);
        android.graphics.drawable.Drawable d = getDrawable(
            android.R.drawable.divider_horizontal_dim_dark);
        if (d != null) {
            d.mutate();
            d.setTintList(android.content.res.ColorStateList.valueOf(0xFF222222));
            divider.setDrawable(d);
        }
        recycler.addItemDecoration(divider);

        // Load icons
        List<IconItem> icons = loadIcons();

        TextView subtitle = findViewById(R.id.subtitle);
        subtitle.setText(icons.size() + " icons from Figma");

        recycler.setAdapter(new IconAdapter(icons));
    }

    private List<IconItem> loadIcons() {
        List<IconItem> items = new ArrayList<>();

        try {
            InputStream is = getAssets().open("figma_assets.json");
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            String json = new String(bytes, StandardCharsets.UTF_8);

            JSONObject root = new JSONObject(json);
            String lastModified = root.optString("lastModified", "");
            String downloadTime = root.optString("downloadTime", "");

            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    String nodeId = a.optString("nodeId", "");
                    String layer = a.optString("layer", "");
                    boolean isRtl = a.optBoolean("isRtl", false);

                    int resId = getResources().getIdentifier(
                        name, "drawable", getPackageName());

                    items.add(new IconItem(name, nodeId, layer, isRtl,
                        name, resId, lastModified, downloadTime));
                }
            }
        } catch (Exception e) {
            // If metadata file doesn't exist (first build), try loading
            // drawables directly via reflection as fallback
            items = loadFromDrawables();
        }

        return items;
    }

    /** Fallback: load icons directly from R.drawable when metadata is missing. */
    private List<IconItem> loadFromDrawables() {
        List<IconItem> items = new ArrayList<>();
        try {
            Class<?> drawableClass = Class.forName(getPackageName() + ".R$drawable");
            for (java.lang.reflect.Field f : drawableClass.getFields()) {
                if (f.getType() == int.class) {
                    String name = f.getName();
                    if (!name.startsWith("ic_launcher") && !name.startsWith("$")) {
                        int resId = f.getInt(null);
                        items.add(new IconItem(name, "", "", false,
                            name, resId, "", ""));
                    }
                }
            }
        } catch (Exception ignored) {}
        return items;
    }
}
