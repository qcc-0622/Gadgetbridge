package nodomain.freeyourgadget.gadgetbridge.database;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把华为手环的数据同步到 XIAOMI_* 表，供橘瓣 (orangechat / me.rerere.rikkahub)
 * 读取。橘瓣硬编码只读 XIAOMI_DAILY_SUMMARY_SAMPLE / XIAOMI_ACTIVITY_SAMPLE /
 * XIAOMI_SLEEP_TIME_SAMPLE / XIAOMI_SLEEP_STAGE_SAMPLE 四张表。
 *
 * 阶段编码映射 (依据 HuaweiSampleProvider.toActivityKind)：
 *   HUAWEI 1 LIGHT_SLEEP    -> XIAOMI 2
 *   HUAWEI 2 REM            -> XIAOMI 4
 *   HUAWEI 3 DEEP_SLEEP     -> XIAOMI 3
 *   HUAWEI 4 AWAKE          -> XIAOMI 5
 *
 * 兼容橘瓣的两个 workaround：
 * 1. STRESS 占位值：橘瓣部分查询用 STRESS > 0 过滤，华为没有压力数据，
 *    同步时对有心率的行填 STRESS=1。
 * 2. 过滤空行：橘瓣取心率用 ORDER BY TIMESTAMP DESC LIMIT 1（只取最新一行），
 *    如果最新行心率为 0 就显示无数据。同步时只保留有心率或有步数的行。
 *
 * 这是幂等操作 - 每次导出前都会用最新 HUAWEI 数据完整重写 XIAOMI 表。
 * 同步失败不会让导出本身失败，只会记录日志。
 */
public class HuaweiToXiaomiSyncer {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiToXiaomiSyncer.class);

    private HuaweiToXiaomiSyncer() {
    }

    /**
     * 在 db 文件被关闭/复制之前调用。
     * 调用方必须已经持有写锁。
     */
    public static void syncIfNeeded(final SQLiteDatabase db) {
        if (db == null) {
            return;
        }
        try {
            if (!hasHuaweiTables(db)) {
                LOG.debug("HUAWEI tables not present, skip sync");
                return;
            }
            if (!hasXiaomiTables(db)) {
                LOG.warn("XIAOMI tables not present, skip sync");
                return;
            }
            db.beginTransaction();
            try {
                syncSleepStage(db);
                syncActivity(db);
                syncSleepTime(db);
                syncDailySummary(db);
                db.setTransactionSuccessful();
                LOG.info("HUAWEI->XIAOMI sync committed");
            } finally {
                db.endTransaction();
            }
        } catch (final Throwable t) {
            // 不要让同步失败阻塞导出
            LOG.error("HUAWEI->XIAOMI sync failed", t);
        }
    }

    private static boolean tableExists(final SQLiteDatabase db, final String name) {
        Cursor c = null;
        try {
            c = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                    new String[]{name});
            return c.moveToFirst();
        } catch (final Throwable t) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean hasHuaweiTables(final SQLiteDatabase db) {
        return tableExists(db, "HUAWEI_ACTIVITY_SAMPLE")
                || tableExists(db, "HUAWEI_SLEEP_STAGE_SAMPLE")
                || tableExists(db, "HUAWEI_SLEEP_STATS_SAMPLE");
    }

    private static boolean hasXiaomiTables(final SQLiteDatabase db) {
        return tableExists(db, "XIAOMI_ACTIVITY_SAMPLE")
                && tableExists(db, "XIAOMI_SLEEP_STAGE_SAMPLE")
                && tableExists(db, "XIAOMI_SLEEP_TIME_SAMPLE")
                && tableExists(db, "XIAOMI_DAILY_SUMMARY_SAMPLE");
    }

    private static void syncSleepStage(final SQLiteDatabase db) {
        if (!tableExists(db, "HUAWEI_SLEEP_STAGE_SAMPLE")) return;
        db.execSQL("DELETE FROM XIAOMI_SLEEP_STAGE_SAMPLE");
        db.execSQL(
                "INSERT INTO XIAOMI_SLEEP_STAGE_SAMPLE (TIMESTAMP, DEVICE_ID, USER_ID, STAGE) " +
                "SELECT TIMESTAMP, DEVICE_ID, USER_ID, " +
                "  CASE STAGE WHEN 1 THEN 2 WHEN 2 THEN 4 WHEN 3 THEN 3 WHEN 4 THEN 5 ELSE STAGE END " +
                "FROM HUAWEI_SLEEP_STAGE_SAMPLE");
    }

    private static void syncActivity(final SQLiteDatabase db) {
        if (!tableExists(db, "HUAWEI_ACTIVITY_SAMPLE")) return;
        db.execSQL("DELETE FROM XIAOMI_ACTIVITY_SAMPLE");
        // 第一步：插入活动数据，STRESS 先用占位值 1（有心率时）
        db.execSQL(
                "INSERT INTO XIAOMI_ACTIVITY_SAMPLE " +
                "  (TIMESTAMP, DEVICE_ID, USER_ID, RAW_INTENSITY, STEPS, RAW_KIND, " +
                "   HEART_RATE, STRESS, SPO2, DISTANCE_CM, ACTIVE_CALORIES, ENERGY) " +
                "SELECT " +
                "  TIMESTAMP, DEVICE_ID, MIN(USER_ID), " +
                "  COALESCE(MAX(CASE WHEN RAW_INTENSITY>=0 THEN RAW_INTENSITY END), -1), " +
                "  COALESCE(MAX(STEPS), 0), " +
                "  COALESCE(MAX(RAW_KIND), -1), " +
                "  COALESCE(MAX(CASE WHEN HEART_RATE BETWEEN 1 AND 250 THEN HEART_RATE END), 0), " +
                "  CASE WHEN MAX(CASE WHEN HEART_RATE BETWEEN 1 AND 250 THEN HEART_RATE END) IS NOT NULL THEN 1 ELSE NULL END, " +
                "  MAX(CASE WHEN SPO BETWEEN 1 AND 100 THEN SPO END), " +
                "  COALESCE(MAX(DISTANCE)*100, 0), " +
                "  COALESCE(MAX(CALORIES), 0), " +
                "  COALESCE(MAX(CALORIES), 0) " +
                "FROM HUAWEI_ACTIVITY_SAMPLE " +
                "GROUP BY TIMESTAMP, DEVICE_ID " +
                "HAVING MAX(CASE WHEN HEART_RATE BETWEEN 1 AND 250 THEN HEART_RATE END) > 0 " +
                "    OR MAX(STEPS) > 0");
        // 第二步：用 HUAWEI_STRESS_SAMPLE 的真实压力值回填
        // GreenDAO 表是 WITHOUT ROWID，不能在 UPDATE 子查询里直接引用外层列，
        // 所以先建临时表做 JOIN，再用 TIMESTAMP+DEVICE_ID 匹配回填。
        // STRESS 表时间戳是毫秒，ACTIVITY 表是秒，匹配最近 5 分钟内的行。
        if (tableExists(db, "HUAWEI_STRESS_SAMPLE")) {
            try {
                db.execSQL("DROP TABLE IF EXISTS _tmp_stress_map");
                db.execSQL(
                        "CREATE TEMP TABLE _tmp_stress_map AS " +
                        "SELECT x.TIMESTAMP AS ts, x.DEVICE_ID AS did, s.STRESS AS real_stress " +
                        "FROM XIAOMI_ACTIVITY_SAMPLE x " +
                        "INNER JOIN HUAWEI_STRESS_SAMPLE s " +
                        "  ON ABS(x.TIMESTAMP - CAST(s.TIMESTAMP/1000 AS INTEGER)) <= 300 " +
                        "  AND s.STRESS BETWEEN 1 AND 100");
                db.execSQL(
                        "UPDATE XIAOMI_ACTIVITY_SAMPLE SET STRESS = (" +
                        "  SELECT real_stress FROM _tmp_stress_map " +
                        "  WHERE _tmp_stress_map.ts = XIAOMI_ACTIVITY_SAMPLE.TIMESTAMP " +
                        "    AND _tmp_stress_map.did = XIAOMI_ACTIVITY_SAMPLE.DEVICE_ID " +
                        "  LIMIT 1" +
                        ") WHERE TIMESTAMP IN (SELECT ts FROM _tmp_stress_map)");
                db.execSQL("DROP TABLE IF EXISTS _tmp_stress_map");
                LOG.info("Backfilled real stress values from HUAWEI_STRESS_SAMPLE");
            } catch (final Throwable t) {
                LOG.warn("Stress backfill failed (non-fatal)", t);
            }
        }
    }

    private static void syncSleepTime(final SQLiteDatabase db) {
        if (!tableExists(db, "HUAWEI_SLEEP_STATS_SAMPLE")) return;
        db.execSQL("DELETE FROM XIAOMI_SLEEP_TIME_SAMPLE");
        db.execSQL(
                "INSERT INTO XIAOMI_SLEEP_TIME_SAMPLE " +
                "  (TIMESTAMP, DEVICE_ID, USER_ID, WAKEUP_TIME, IS_AWAKE, " +
                "   TOTAL_DURATION, DEEP_SLEEP_DURATION, LIGHT_SLEEP_DURATION, " +
                "   REM_SLEEP_DURATION, AWAKE_DURATION) " +
                "SELECT " +
                "  s.BED_TIME, s.DEVICE_ID, s.USER_ID, " +
                "  s.WAKEUP_TIME, 0, " +
                "  CAST((s.WAKEUP_TIME - s.BED_TIME)/60000 AS INTEGER), " +
                "  COALESCE((SELECT COUNT(*) FROM HUAWEI_SLEEP_STAGE_SAMPLE g " +
                "            WHERE g.DEVICE_ID=s.DEVICE_ID AND g.TIMESTAMP>=s.BED_TIME " +
                "              AND g.TIMESTAMP<s.WAKEUP_TIME AND g.STAGE=3), 0), " +
                "  COALESCE((SELECT COUNT(*) FROM HUAWEI_SLEEP_STAGE_SAMPLE g " +
                "            WHERE g.DEVICE_ID=s.DEVICE_ID AND g.TIMESTAMP>=s.BED_TIME " +
                "              AND g.TIMESTAMP<s.WAKEUP_TIME AND g.STAGE=1), 0), " +
                "  COALESCE((SELECT COUNT(*) FROM HUAWEI_SLEEP_STAGE_SAMPLE g " +
                "            WHERE g.DEVICE_ID=s.DEVICE_ID AND g.TIMESTAMP>=s.BED_TIME " +
                "              AND g.TIMESTAMP<s.WAKEUP_TIME AND g.STAGE=2), 0), " +
                "  COALESCE((SELECT COUNT(*) FROM HUAWEI_SLEEP_STAGE_SAMPLE g " +
                "            WHERE g.DEVICE_ID=s.DEVICE_ID AND g.TIMESTAMP>=s.BED_TIME " +
                "              AND g.TIMESTAMP<s.WAKEUP_TIME AND g.STAGE=4), 0) " +
                "FROM HUAWEI_SLEEP_STATS_SAMPLE s");
    }

    private static void syncDailySummary(final SQLiteDatabase db) {
        if (!tableExists(db, "HUAWEI_ACTIVITY_SAMPLE")) return;
        db.execSQL("DELETE FROM XIAOMI_DAILY_SUMMARY_SAMPLE");
        // STRESS_AVG: 从 HUAWEI_STRESS_SAMPLE 按天计算平均值，没有则填 1（占位）
        final String stressAvgExpr;
        if (tableExists(db, "HUAWEI_STRESS_SAMPLE")) {
            stressAvgExpr =
                    "COALESCE((SELECT CAST(AVG(s.STRESS) AS INTEGER) " +
                    "  FROM HUAWEI_STRESS_SAMPLE s " +
                    "  WHERE s.STRESS BETWEEN 1 AND 100 " +
                    "    AND s.TIMESTAMP/1000 >= (strftime('%s', date(a.TIMESTAMP+28800,'unixepoch'))-28800) " +
                    "    AND s.TIMESTAMP/1000 < (strftime('%s', date(a.TIMESTAMP+28800,'unixepoch'))-28800)+86400" +
                    "), 1)";
        } else {
            stressAvgExpr = "1";
        }
        db.execSQL(
                "INSERT INTO XIAOMI_DAILY_SUMMARY_SAMPLE " +
                "  (TIMESTAMP, DEVICE_ID, USER_ID, TIMEZONE, STEPS, " +
                "   HR_RESTING, HR_MAX, HR_MIN, HR_AVG, STRESS_AVG, CALORIES, SPO2_AVG) " +
                "SELECT " +
                "  (strftime('%s', date(a.TIMESTAMP+28800,'unixepoch'))-28800)*1000 AS day_ms, " +
                "  a.DEVICE_ID, MIN(a.USER_ID), 28800, " +
                "  SUM(CASE WHEN a.STEPS>0 THEN a.STEPS ELSE 0 END), " +
                "  MAX(CASE WHEN a.RESTING_HEART_RATE BETWEEN 30 AND 200 THEN a.RESTING_HEART_RATE END), " +
                "  MAX(CASE WHEN a.HEART_RATE BETWEEN 30 AND 220 THEN a.HEART_RATE END), " +
                "  MIN(CASE WHEN a.HEART_RATE BETWEEN 30 AND 220 THEN a.HEART_RATE END), " +
                "  CAST(AVG(CASE WHEN a.HEART_RATE BETWEEN 30 AND 220 THEN a.HEART_RATE END) AS INTEGER), " +
                "  " + stressAvgExpr + ", " +
                "  SUM(CASE WHEN a.CALORIES>0 THEN a.CALORIES ELSE 0 END), " +
                "  CAST(AVG(CASE WHEN a.SPO BETWEEN 1 AND 100 THEN a.SPO END) AS INTEGER) " +
                "FROM HUAWEI_ACTIVITY_SAMPLE a " +
                "GROUP BY day_ms, a.DEVICE_ID");
    }
}
