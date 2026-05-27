package com.myonlinetime.app.workers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.myonlinetime.app.VpsApi;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Гарантированный синк описания приложения на сервер.
 *
 * Контракт:
 *  - Запись о намерении хранится в SharedPreferences "PendingDescSync_<uid>" → key "pending"
 *    (JSON map pkgName → description). Это единственный источник правды.
 *  - enqueue(...) добавляет запись в prefs (если ещё не там) и шедулит уникальный Worker
 *    с ключом "desc_sync_<uid>_<pkg>". REPLACE — последний вызов всегда побеждает.
 *  - flushPending(...) ре-шедулит все pending для данного uid (вызывать при входе в аккаунт).
 *  - Worker уходит на сервер только когда: есть сеть, текущий signed-in аккаунт == uid задачи,
 *    в prefs UserProfile есть vps_token.
 *  - При успехе удаляет из pending именно то значение, что отправил (защита от race с новой
 *    правкой пользователя — её отдельный Worker дошлёт следом).
 *  - При фейле — Result.retry() с exponential backoff, до 10 попыток.
 */
public class SyncDescriptionWorker extends Worker {

    public static final String KEY_UID = "uid";
    public static final String KEY_PKG = "pkg";
    public static final String KEY_DESC = "desc";

    public static final String PENDING_DESC_PREFS_PREFIX = "PendingDescSync_";
    public static final String PENDING_DESC_KEY = "pending";

    private static final String USER_PREFS_NAME = "UserProfile";
    private static final String VPS_TOKEN_KEY = "vps_token";

    private static final Gson gson = new Gson();

    public SyncDescriptionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        final Context appCtx = getApplicationContext();
        final Data data = getInputData();
        final String uid = data.getString(KEY_UID);
        final String pkg = data.getString(KEY_PKG);
        final String descArg = data.getString(KEY_DESC);

        if (uid == null || uid.isEmpty() || pkg == null) return Result.success();

        // Сейчас залогинен другой аккаунт — не трогаем сервер, оставляем запись
        // в pending. При возврате в этот аккаунт ProfileFragment вызовет flushPending().
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(appCtx);
        if (account == null || !uid.equals(account.getId())) {
            return Result.success();
        }

        SharedPreferences userPrefs = appCtx.getSharedPreferences(USER_PREFS_NAME, Context.MODE_PRIVATE);
        String token = userPrefs.getString(VPS_TOKEN_KEY, null);
        if (token == null || token.isEmpty()) {
            return getRunAttemptCount() < 10 ? Result.retry() : Result.success();
        }

        // Берём самое свежее значение из pending — пользователь мог перебить
        Map<String, String> pending = readPending(appCtx, uid);
        String latestDesc = pending.containsKey(pkg) ? pending.get(pkg) : descArg;
        if (latestDesc == null) latestDesc = "";
        final String sent = latestDesc;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);

        try {
            VpsApi.setAppDescription(token, pkg, sent, new VpsApi.Callback() {
                @Override public void onSuccess(String result) { ok.set(true);  latch.countDown(); }
                @Override public void onError(String error)    { ok.set(false); latch.countDown(); }
            });
        } catch (Throwable t) {
            return getRunAttemptCount() < 10 ? Result.retry() : Result.success();
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry();
        }

        if (ok.get()) {
            // Удаляем из pending только если значение всё ещё совпадает с отправленным.
            // Если пользователь успел поменять — запись остаётся, и её догонит свой Worker.
            Map<String, String> p2 = readPending(appCtx, uid);
            String stored = p2.get(pkg);
            if (stored != null && stored.equals(sent)) {
                p2.remove(pkg);
                writePending(appCtx, uid, p2);
            }
            return Result.success();
        }
        return getRunAttemptCount() < 10 ? Result.retry() : Result.success();
    }

    // === Public API ===

    /** Сохраняет в pending и шедулит уникальный Worker. Безопасно вызывать с любого потока. */
    public static void enqueue(Context ctx, String uid, String pkg, String desc) {
        if (ctx == null || uid == null || uid.isEmpty() || pkg == null) return;
        Context appCtx = ctx.getApplicationContext();

        Map<String, String> pending = readPending(appCtx, uid);
        pending.put(pkg, desc != null ? desc : "");
        writePending(appCtx, uid, pending);

        scheduleWorker(appCtx, uid, pkg, desc);
    }

    /** Ре-шедулит все pending-записи для данного uid. Вызывать при входе пользователя в аккаунт. */
    public static void flushPending(Context ctx, String uid) {
        if (ctx == null || uid == null || uid.isEmpty()) return;
        Context appCtx = ctx.getApplicationContext();
        Map<String, String> pending = readPending(appCtx, uid);
        if (pending.isEmpty()) return;
        for (Map.Entry<String, String> e : pending.entrySet()) {
            scheduleWorker(appCtx, uid, e.getKey(), e.getValue());
        }
    }

    private static void scheduleWorker(Context appCtx, String uid, String pkg, String desc) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        Data input = new Data.Builder()
                .putString(KEY_UID, uid)
                .putString(KEY_PKG, pkg)
                .putString(KEY_DESC, desc != null ? desc : "")
                .build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncDescriptionWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setInputData(input)
                .build();
        WorkManager.getInstance(appCtx)
                .enqueueUniqueWork("desc_sync_" + uid + "_" + pkg, ExistingWorkPolicy.REPLACE, req);
    }

    // === Pending prefs helpers ===

    private static Map<String, String> readPending(Context appCtx, String uid) {
        SharedPreferences p = appCtx.getSharedPreferences(PENDING_DESC_PREFS_PREFIX + uid, Context.MODE_PRIVATE);
        String json = p.getString(PENDING_DESC_KEY, "{}");
        try {
            Map<String, String> m = gson.fromJson(json, new TypeToken<Map<String, String>>(){}.getType());
            return m != null ? m : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static void writePending(Context appCtx, String uid, Map<String, String> map) {
        SharedPreferences p = appCtx.getSharedPreferences(PENDING_DESC_PREFS_PREFIX + uid, Context.MODE_PRIVATE);
        p.edit().putString(PENDING_DESC_KEY, gson.toJson(map)).apply();
    }
}
