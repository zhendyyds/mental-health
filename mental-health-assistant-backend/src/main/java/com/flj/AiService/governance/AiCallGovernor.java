package com.flj.AiService.governance;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Reserves capacity before initialization, charges a call only at dispatch, and
 * releases capacity when the actual task exits. State is local to this JVM.
 */
@Component
public class AiCallGovernor {
    private static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Shanghai");
    private final AiProtectionProperties limits;
    private final Clock clock;
    private int activeCalls;
    private DayUsage currentDay;

    @Autowired
    public AiCallGovernor(AiProtectionProperties limits) {
        this(limits, Clock.system(BUDGET_ZONE));
    }

    public AiCallGovernor(AiProtectionProperties limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock).withZone(BUDGET_ZONE);
        if (!limits.isInputBudgetValid() || limits.getMaxConcurrent() < 1
                || limits.getUserRequestsPerMinute() < 1 || limits.getUserDailyCalls() < 1
                || limits.getGlobalDailyCalls() < 1 || limits.getMaxTrackedUsers() < 1
                || limits.getMaxOutputTokens() < 1 || limits.getMaxReplyBytes() < 1
                || limits.getMaxReplyFragments() < 1 || limits.getReservedInputUnits() < 0) {
            throw new IllegalArgumentException("AI protection limits must be positive and internally consistent");
        }
        this.currentDay = new DayUsage(LocalDate.now(this.clock));
    }

    public synchronized Lease acquire(Long userId, String scene) {
        if (userId == null || userId <= 0 || scene == null || scene.isBlank()) {
            throw new AiCallRejectedException("400", "无法确认模型调用身份", 0);
        }
        LocalDate today = LocalDate.now(clock);
        if (!currentDay.date.equals(today)) currentDay = new DayUsage(today);
        if (activeCalls >= limits.getMaxConcurrent()) {
            throw new AiCallRejectedException("429", "模型服务繁忙，请稍后再试", 0);
        }
        if (currentDay.calls >= limits.getGlobalDailyCalls()) {
            throw new AiCallRejectedException("429", "今日模型调用总预算已用完", secondsUntilNextDay());
        }
        UserUsage usage = currentDay.users.get(userId);
        long minute = Math.floorDiv(clock.millis(), 60000);
        if (usage == null) {
            if (currentDay.users.size() >= limits.getMaxTrackedUsers()) {
                throw new AiCallRejectedException("429", "模型调用记录容量已满，请稍后再试", secondsUntilNextDay());
            }
            usage = new UserUsage(minute);
            currentDay.users.put(userId, usage);
        }
        if (usage.calls >= limits.getUserDailyCalls()) {
            throw new AiCallRejectedException("429", "今日个人模型调用预算已用完", secondsUntilNextDay());
        }
        if (usage.minute != minute) {
            usage.minute = minute;
            usage.minuteCalls = 0;
        }
        if (usage.minuteCalls >= limits.getUserRequestsPerMinute()) {
            int wait = 60 - (int) Math.floorMod(clock.instant().getEpochSecond(), 60);
            throw new AiCallRejectedException("429", "模型请求过于频繁，请稍后再试", wait);
        }
        activeCalls++;
        currentDay.calls++;
        usage.calls++;
        usage.minuteCalls++;
        return new Lease(userId, currentDay, usage, minute);
    }

    public int getMaxOutputTokens() { return limits.getMaxOutputTokens(); }
    public int getMaxReplyBytes() { return limits.getMaxReplyBytes(); }
    public int getMaxReplyFragments() { return limits.getMaxReplyFragments(); }

    private int secondsUntilNextDay() {
        long seconds = Duration.between(clock.instant(), LocalDate.now(clock).plusDays(1)
                .atStartOfDay(BUDGET_ZONE).toInstant()).getSeconds();
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    /** UTF-8 bytes are a conservative input unit, not a provider token count. */
    public static long utf8Bytes(String value) {
        if (value == null) return 0;
        long bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x80) bytes++;
            else if (character < 0x800) bytes += 2;
            else if (Character.isHighSurrogate(character) && index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1))) {
                bytes += 4;
                index++;
            } else bytes += 3;
        }
        return bytes;
    }

    public final class Lease implements AutoCloseable {
        private final Long userId;
        private final DayUsage day;
        private final UserUsage usage;
        private final long minute;
        private boolean started;
        private boolean closed;

        private Lease(Long userId, DayUsage day, UserUsage usage, long minute) {
            this.userId = userId;
            this.day = day;
            this.usage = usage;
            this.minute = minute;
        }

        public void validateInput(String... material) {
            synchronized (AiCallGovernor.this) { checkOpen(); }
            long units = limits.getReservedInputUnits();
            if (material != null) {
                for (String part : material) {
                    units += utf8Bytes(part);
                    if (units > limits.getMaxInputUnits()) {
                        throw new AiCallRejectedException("400", "输入内容超过模型调用预算，请缩短内容或新建会话", 0);
                    }
                }
            }
        }

        /** Failed dispatched calls count too; pre-dispatch failures are refunded. */
        public void markStarted() {
            synchronized (AiCallGovernor.this) {
                checkOpen();
                started = true;
            }
        }

        private void checkOpen() {
            if (closed) throw new IllegalStateException("Model lease already closed");
        }

        @Override
        public void close() {
            synchronized (AiCallGovernor.this) {
                if (closed) return;
                closed = true;
                activeCalls--;
                if (!started) {
                    day.calls--;
                    usage.calls--;
                    if (usage.minute == minute) usage.minuteCalls--;
                    if (usage.calls == 0 && usage.minuteCalls == 0) day.users.remove(userId, usage);
                }
            }
        }
    }

    private static final class DayUsage {
        private final LocalDate date;
        private final Map<Long, UserUsage> users = new HashMap<>();
        private int calls;
        private DayUsage(LocalDate date) { this.date = date; }
    }

    private static final class UserUsage {
        private long minute;
        private int minuteCalls;
        private int calls;
        private UserUsage(long minute) { this.minute = minute; }
    }
}
