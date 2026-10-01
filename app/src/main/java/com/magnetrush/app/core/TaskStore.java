package com.magnetrush.app.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务表 + 断点续传数据的落盘存储。
 *
 * <p>分两层：
 * <ul>
 *   <li>任务表本身放在 SharedPreferences（小而少读，用 JSON 存最省事）；</li>
 *   <li>libtorrent 的 resume data 放在 filesDir/resume/&lt;hash&gt;.fastresume，
 *       这是二进制 blob，可能几十 KB，不能塞进 SharedPreferences。</li>
 * </ul>
 */
public final class TaskStore {

    private static final String TAG = "TaskStore";
    private static final String PREF = "magnetrush_tasks";
    private static final String KEY_TASKS = "tasks_json";

    private static final String RESUME_DIR = "resume";

    private static volatile TaskStore instance;

    private final SharedPreferences prefs;
    private final File resumeDir;

    /** infoHash -> 任务，保持插入顺序便于稳定展示 */
    private final Map<String, TorrentTask> tasks = new LinkedHashMap<>();

    private TaskStore(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
        this.resumeDir = new File(ctx.getApplicationContext().getFilesDir(), RESUME_DIR);
        if (!resumeDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            resumeDir.mkdirs();
        }
        load();
    }

    public static TaskStore get(Context ctx) {
        TaskStore local = instance;
        if (local == null) {
            synchronized (TaskStore.class) {
                local = instance;
                if (local == null) {
                    local = new TaskStore(ctx);
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------ 任务表

    private void load() {
        tasks.clear();
        String json = prefs.getString(KEY_TASKS, null);
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                TorrentTask t = TorrentTask.fromJson(o);
                if (t.infoHash != null && !t.infoHash.isEmpty()) {
                    tasks.put(t.infoHash, t);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load tasks failed", e);
        }
    }

    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (tasks) {
                for (TorrentTask t : tasks.values()) {
                    arr.put(t.toJson());
                }
            }
            prefs.edit().putString(KEY_TASKS, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "persist tasks failed", e);
        }
    }

    public List<TorrentTask> all() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    /** 展示顺序：未完成的在前，各自按添加时间倒序 */
    public List<TorrentTask> sortedForDisplay() {
        List<TorrentTask> list = all();
        Collections.sort(list, new Comparator<TorrentTask>() {
            @Override
            public int compare(TorrentTask a, TorrentTask b) {
                boolean ad = a.isCompleted();
                boolean bd = b.isCompleted();
                if (ad != bd) {
                    return ad ? 1 : -1;
                }
                return Long.compare(b.addedAt, a.addedAt);
            }
        });
        return list;
    }

    public TorrentTask get(String infoHash) {
        if (infoHash == null) {
            return null;
        }
        synchronized (tasks) {
            return tasks.get(infoHash.toLowerCase());
        }
    }

    public boolean contains(String infoHash) {
        return get(infoHash) != null;
    }

    public void put(TorrentTask task) {
        if (task == null || task.infoHash == null) {
            return;
        }
        task.infoHash = task.infoHash.toLowerCase();
        synchronized (tasks) {
            tasks.put(task.infoHash, task);
        }
        persist();
    }

    public void remove(String infoHash) {
        if (infoHash == null) {
            return;
        }
        synchronized (tasks) {
            tasks.remove(infoHash.toLowerCase());
        }
        deleteResumeData(infoHash);
        persist();
    }

    /** 只更新内存不立刻落盘，用于每秒一次的高频更新，由调用方决定何时 flush */
    public void updateInMemory(TorrentTask task) {
        if (task == null || task.infoHash == null) {
            return;
        }
        synchronized (tasks) {
            tasks.put(task.infoHash.toLowerCase(), task);
        }
    }

    public void flush() {
        persist();
    }

    // -------------------------------------------------- 断点续传数据

    private File resumeFile(String infoHash) {
        return new File(resumeDir, infoHash.toLowerCase() + ".fastresume");
    }

    /**
     * 保存 libtorrent 给出的 resume data。
     *
     * <p>说明一下当前的实际用途：libtorrent4j 2.1.0-39 只暴露了「写」resume data 的
     * Java API，没有暴露「读」的 API，所以进程重启后走的是「重新添加磁链 + 重新校验」
     * 这条等价路径（见 {@code TorrentEngine.restore}），不会重下已完成的字节。
     *
     * <p>那为什么还存？因为它是标准的 bencode 快速恢复数据，里面包含了分片状态和
     * 文件优先级。将来如果：
     * <ul>
     *   <li>libtorrent4j 补上了读入 API，或者</li>
     *   <li>我们自己在 Java 层解析这份 bencode（格式是公开的）</li>
     * </ul>
     * 就能直接跳过校验，秒级恢复。现在存下来是零成本的（几十 KB），
     * 以后要用的时候历史任务也有数据可用。
     */
    public void saveResumeData(String infoHash, byte[] data) {
        if (infoHash == null || data == null || data.length == 0) {
            return;
        }
        File f = resumeFile(infoHash);
        File tmp = new File(resumeDir, f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "save resume data failed for " + infoHash, e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        // 先写临时文件再改名，避免掉电时留下半个文件
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        }
    }

    public byte[] loadResumeData(String infoHash) {
        if (infoHash == null) {
            return null;
        }
        File f = resumeFile(infoHash);
        if (!f.exists() || f.length() == 0) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "load resume data failed for " + infoHash, e);
            return null;
        }
    }

    public boolean hasResumeData(String infoHash) {
        return infoHash != null && resumeFile(infoHash).length() > 0;
    }

    public void deleteResumeData(String infoHash) {
        if (infoHash == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        resumeFile(infoHash).delete();
    }

    /** resume data 目录，交给 libtorrent 自己用（session 级快速恢复缓存） */
    public File resumeDir() {
        return resumeDir;
    }

    /** 清掉所有任务的断点数据（设置里的「重置」用） */
    public void clearAllResumeData() {
        File[] files = resumeDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
