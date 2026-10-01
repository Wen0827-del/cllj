package com.magnetrush.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.magnetrush.app.R;
import com.magnetrush.app.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/** 任务详情里的文件列表（可勾选要下哪些文件）。 */
public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileHolder> {

    public interface OnToggle {
        void onToggle(int index, boolean wanted);
    }

    public static final class FileItem {
        public final String path;
        public final long size;
        public final long done;
        public boolean wanted;

        FileItem(String path, long size, long done, boolean wanted) {
            this.path = path;
            this.size = size;
            this.done = done;
            this.wanted = wanted;
        }

        float progress() {
            if (size <= 0) {
                return 0f;
            }
            return Math.max(0f, Math.min(1f, done / (float) size));
        }
    }

    private final List<FileItem> items = new ArrayList<>();
    private final OnToggle toggle;

    public FileAdapter(OnToggle toggle) {
        this.toggle = toggle;
        setHasStableIds(false);
    }

    public void submit(List<FileItem> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    /** 当前勾选状态，供「应用」时写回引擎 */
    public int[] snapshot() {
        int[] out = new int[items.size()];
        for (int i = 0; i < items.size(); i++) {
            out[i] = items.get(i).wanted ? 4 : 0;
        }
        return out;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public FileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FileHolder h, int position) {
        FileItem f = items.get(position);
        h.name.setText(f.path);
        h.meta.setText(Fmt.size(f.size) + " · 已完成 " + Fmt.percent(f.progress()));

        // 先解绑，避免复用时触发旧监听
        h.toggle.setOnCheckedChangeListener(null);
        h.toggle.setChecked(f.wanted);
        h.toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int idx = h.getBindingAdapterPosition();
            if (idx == RecyclerView.NO_POSITION || idx >= items.size()) {
                return;
            }
            items.get(idx).wanted = isChecked;
            if (toggle != null) {
                toggle.onToggle(idx, isChecked);
            }
        });
    }

    static class FileHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final MaterialSwitch toggle;

        FileHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.file_name);
            meta = itemView.findViewById(R.id.file_meta);
            toggle = itemView.findViewById(R.id.file_switch);
        }
    }
}
