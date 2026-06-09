package com.example.figmademo;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class IconAdapter extends RecyclerView.Adapter<IconAdapter.ViewHolder> {

    private final List<IconItem> items;

    public IconAdapter(List<IconItem> items) {
        this.items = items;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_icon, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        IconItem item = items.get(position);

        // Icon — 56dp × 56dp
        if (item.drawableResId != 0) {
            holder.icon.setImageResource(item.drawableResId);
            holder.icon.setColorFilter(Color.WHITE & 0xCCFFFFFF);
        } else {
            holder.icon.setImageResource(android.R.drawable.ic_menu_help);
            holder.icon.setColorFilter(Color.WHITE & 0x44FFFFFF);
        }

        // Name
        String displayName = item.name.replace("icon_", "").replace("_", " ");
        holder.name.setText(displayName);

        // Layer
        if (item.layer != null && !item.layer.isEmpty()) {
            holder.layer.setText("Layer: " + item.layer);
            holder.layer.setVisibility(View.VISIBLE);
        } else {
            holder.layer.setVisibility(View.GONE);
        }

        // Edit time
        String editTime = item.formatEditTime();
        if (!editTime.isEmpty()) {
            holder.editTime.setText(editTime);
            holder.editTime.setVisibility(View.VISIBLE);
        } else {
            holder.editTime.setVisibility(View.GONE);
        }

        // RTL badge
        holder.rtlBadge.setVisibility(item.isRtl ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView layer;
        final TextView editTime;
        final TextView rtlBadge;

        ViewHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.icon);
            name = v.findViewById(R.id.name);
            layer = v.findViewById(R.id.layer);
            editTime = v.findViewById(R.id.edit_time);
            rtlBadge = v.findViewById(R.id.rtl_badge);
        }
    }
}
