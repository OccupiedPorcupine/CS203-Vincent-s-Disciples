package app.closure;

import java.time.Duration;
import java.time.LocalTime;
import java.util.OptionalLong;

// Opening and closing time of a trading day. closesAt before opensAt means the stall closes
// after midnight, e.g. a supper stall open 18:00 to 02:00.
public record Hours(LocalTime opensAt, LocalTime closesAt) {

    // Used when a stall hasn't set its hours.
    public static final Hours DEFAULT = new Hours(LocalTime.of(8, 0), LocalTime.of(20, 0));

    public Hours {
        if (opensAt == null || closesAt == null) throw new IllegalArgumentException("Give both an opening and a closing time.");
        if (opensAt.equals(closesAt)) throw new IllegalArgumentException("The closing time must be different from the opening time.");
    }

    public long minutesOpen() {
        return minutesFromOpening(closesAt);
    }

    // Minutes from opening to the given time, or empty if the stall isn't open then.
    public OptionalLong minutesUntil(LocalTime time) {
        long minutes = minutesFromOpening(time);
        return minutes <= minutesOpen() ? OptionalLong.of(minutes) : OptionalLong.empty();
    }

    private long minutesFromOpening(LocalTime time) {
        return Math.floorMod(Duration.between(opensAt, time).toMinutes(), 24 * 60);
    }

    @Override
    public String toString() {
        return opensAt + "–" + closesAt;
    }
}
