package ru.souz.tool.calendar

import ru.souz.llms.restJsonMapper
import ru.souz.tool.BadInputException
import ru.souz.tool.ToolRunBashCommand
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

internal object CalendarEventKit {
    data class Event(val title: String, val startDate: ZonedDateTime, val isAllDay: Boolean)

    fun listEvents(calendarName: String, date: String?, bash: ToolRunBashCommand): List<Event> {
        val day = try {
            if (date.isNullOrBlank()) LocalDate.now() else LocalDate.parse(date)
        } catch (_: DateTimeParseException) {
            throw BadInputException("Invalid date '$date'; expected YYYY-MM-DD")
        }
        val zone = ZoneId.systemDefault()
        val result = bash.javaScript(
            script,
            listOf(calendarName, day.atStartOfDay(zone).toEpochSecond().toString(),
                day.plusDays(1).atStartOfDay(zone).toEpochSecond().toString()),
            timeoutMillis = 35_000,
        )
        return restJsonMapper.readTree(result).map {
            Event(it["title"].asText(), Instant.ofEpochSecond(it["start"].asLong()).atZone(zone), it["allDay"].asBoolean())
        }.sortedBy { it.startDate }
    }

    // EventKit expands recurring events and exceptions for the requested day. No Calendar app or private database.
    internal val script = """
        ObjC.import('Foundation');
        ObjC.import('EventKit');
        var EventStore = $.EKEventStore;

        function run(argv) {
            var store = EventStore.alloc.init;
            var status = Number(EventStore.authorizationStatusForEntityType(0));
            if (status === 0 || status === 4) {
                // Do not read the store until full access has been decided (4 means write-only).
                if (store.respondsToSelector('requestFullAccessToEventsWithCompletion:')) {
                    store.requestFullAccessToEventsWithCompletion(function() {});
                } else {
                    store.requestAccessToEntityTypeCompletion(0, function() {});
                }
                var deadline = Date.now() + 30000;
                do {
                    $.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.05));
                    status = Number(EventStore.authorizationStatusForEntityType(0));
                } while ((status === 0 || status === 4) && Date.now() < deadline);
                store.reset;
            }
            if (status !== 3) {
                throw new Error('Calendar full access is required. Check System Settings > Privacy & Security > Calendars (status ' + status + ').');
            }
            var calendars = store.calendarsForEntityType(0);
            var selected = $.NSMutableArray.array;
            for (var i = 0; i < calendars.count; i++) {
                var calendar = calendars.objectAtIndex(i);
                if (ObjC.unwrap(calendar.title) === argv[0]) selected.addObject(calendar);
            }
            if (Number(selected.count) === 0) throw new Error('Calendar not found: ' + argv[0]);
            var start = $.NSDate.dateWithTimeIntervalSince1970(Number(argv[1]));
            var end = $.NSDate.dateWithTimeIntervalSince1970(Number(argv[2]));
            var predicate = store.predicateForEventsWithStartDateEndDateCalendars(start, end, selected);
            var events = store.eventsMatchingPredicate(predicate);
            var result = [];
            for (var j = 0; j < events.count; j++) {
                var event = events.objectAtIndex(j);
                result.push({title: ObjC.unwrap(event.title) || '',
                    start: Number(event.startDate.timeIntervalSince1970), allDay: Boolean(Number(event.allDay))});
            }
            return JSON.stringify(result);
        }
    """.trimIndent()
}
