package com.magnetrush.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.magnetrush.app.R;
import com.magnetrush.app.core.TaskStatus;
import com.magnetrush.app.service.TorrentView;
import com.magnetrush.app.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/** 任务列表适配器。数据源是服务每秒广播过来的 {@link TorrentView} 快照。 */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.RowHolder> {

    public interface RowListener {
        void onRowClick(TorrentView view);

        void onRowAction(TorrentView view);
    }

    private final List<TorrentView> items = new ArrayList<>();
    private final RowListener listener;

    public TaskAdapter(RowListener listener) {
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<TorrentView> data) {
        items.clear();
        if (data != null) {
            items.addAll(data);
        }
        notifyDataSetChanged();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).infoHash.hashCode();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task, parent, false);
        return new RowHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder h, int position) {
        TorrentView v = items.get(position);

        h.name.setText(v.name);

        float pct = Math.max(0f, Math.min(1f, v.progress));
        if (v.totalBytes <= 0 && v.isRunning()) {
            // 元数据还没到，进度条做成不确定态
            h.progress.setIndeterminate(true);
        } else {
            h.progress.setIndeterminate(false);
            h.progress.setProgress((int) (pct * 1000f));
        }

        h.status.setText(statusLine(v));
        h.status.setTextColor(h.itemView.getContext().getColor(
                v.isError() ? R.color.state_error
                        : v.completed ? R.color.state_done
                        : R.color.state_normal));

        // 右侧按钮：运行中显示暂停，否则显示继续
        boolean running = v.isRunning();
        h.action.setImageResource(running ? R.drawable.ic_pause : R.drawable.ic_play);
        h.action.setContentDescription(h.itemView.getContext().getString(
                running ? R.string.action_pause : R.string.action_resume));

        h.itemView.setOnClickListener(view -> listener.onRowClick(v));
        h.action.setOnClickListener(view -> listener.onRowAction(v));
    }

    /** 状态行文案：把「在干什么 + 速度 + 节点数」压成一行 */
    private String statusLine(TorrentView v) {
        TaskStatus.State s = v.stateEnum();
        switch (s) {
            case CHECKING:
                return "校验已有数据… " + Fmt.percent(v.progress);
            case FETCHING_METADATA:
                return "正在从 DHT / Tracker 获取元数据…";
            case QUEUED:
                return "排队中…";
            case FINISHED:
                return "已完成 · " + Fmt.size(v.totalBytes) + " · " + Fmt.ago(v.completedAt);
            case SEEDING:
                return "已完成（做种中） · " + Fmt.size(v.totalBytes)
                        + " · ↑ " + Fmt.speed(v.uploadRate);
            case PAUSED:
                return "已暂停 · " + Fmt.percent(v.progress)
                        + (v.totalBytes > 0 ? " · " + Fmt.size(v.totalBytes) : "");
            case ERROR:
                return "出错：" + (v.error != null ? v.error : "未知原因");
            case DOWNLOADING:
            default: {
                StringBuilder sb = new StringBuilder();
                if (v.totalBytes > 0) {
                    sb.append(Fmt.size(v.downloadedBytes))
                            .append(" / ")
                            .append(Fmt.size(v.totalBytes))
                            .append("  ·  ")
                            .append(Fmt.percent(v.progress));
                } else {
                    sb.append("已下载 ").append(Fmt.size(v.downloadedBytes));
                }
                sb.append("\n↓ ").append(Fmt.speed(v.downloadRate));
                if (v.uploadRate > 0) {
                    sb.append("   ↑ ").append(Fmt.speed(v.uploadRate));
                }
                sb.append("   ").append(v.peers).append(" 节点");
                if (v.seeds > 0) {
                    sb.append("（含 ").append(v.seeds).append(" 种子）");
                }
                long remain = v.remainBytes();
                if (remain > 0 && v.downloadRate > 0) {
                    sb.append("   剩余 ").append(Fmt.eta(remain, v.downloadRate));
                }
                return sb.toString();
            }
        }
    }

    static class RowHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView status;
        final LinearProgressIndicator progress;
        final ImageButton action;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.task_name);
            status = itemView.findViewById(R.id.task_status);
            progress = itemView.findViewById(R.id.task_progress);
            action = itemView.findViewById(R.id.task_action);
        }
    }
}
