package ru.souz.tool.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ShellException
import ru.souz.tool.ToolRunBashCommand
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

@EnabledOnOs(OS.MAC)
class CalendarEventKitTest {
    @ParameterizedTest
    @ValueSource(ints = [0, 3, 4])
    fun `production script requests full access before querying and returns native occurrences`(status: Int) {
        val result = ToolRunBashCommand.javaScript(fixture(status), args, 5_000)
        val events = restJsonMapper.readTree(result)
        assertEquals(2, events.size())
        assertEquals("Recurring occurrence", events[0]["title"].asText())
        assertEquals(1_800_000_000, events[0]["start"].asLong())
        assertEquals(true, events[1]["allDay"].asBoolean())
    }

    @Test
    fun `older macOS uses the legacy permission API and empty results stay empty`() {
        assertEquals("[]", ToolRunBashCommand.javaScript(fixture(0, modern = false, empty = true), args, 5_000))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `denied or restricted access fails clearly without reading calendars`(status: Int) {
        val error = assertFailsWith<ShellException> {
            ToolRunBashCommand.javaScript(fixture(status, granted = false), args, 5_000)
        }
        assertTrue(error.message.orEmpty().contains("Calendar full access is required"), error.message)
    }

    @Test
    fun `unknown calendar fails instead of appearing empty`() {
        val script = fixture(3)
            .replace("var selected = fixtureSelection;", "var selected = $.NSMutableArray.array;")
            .replace("return array([fixtureCalendar]);", "return array([]);")
        val error = assertFailsWith<ShellException> {
            ToolRunBashCommand.javaScript(script, listOf("Missing", args[1], args[2]), 5_000)
        }
        assertTrue(error.message.orEmpty().contains("Calendar not found: Missing"), error.message)
    }

    private val args = listOf("Fixture", "1799971200", "1800057600")

    // Run production JXA with a synthetic EventKit store. Foundation and OSA are real;
    // no event store is instantiated, permissions requested, or personal data accessed.
    private fun fixture(status: Int, modern: Boolean = true, granted: Boolean = true, empty: Boolean = false): String {
        val production = CalendarEventKit.script
            .replace("var EventStore = $.EKEventStore;", "var EventStore = fixtureEventStore;")
            .replace("var selected = $.NSMutableArray.array;", "var selected = fixtureSelection;")
            .replace("function run(argv)", "function listFixture(argv)")
        val events = if (empty) "[]" else """[
            {title: ${'$'}('Recurring occurrence'), startDate: ${'$'}.NSDate.dateWithTimeIntervalSince1970(1800000000), allDay: false},
            {title: ${'$'}('All day'), startDate: ${'$'}.NSDate.dateWithTimeIntervalSince1970(1799971200), allDay: true}
        ]"""
        return """
            ObjC.import('Foundation');
            var status = $status, requests = 0, resets = 0, queries = 0;
            var fixtureCalendar = {title: ${'$'}('Fixture')};
            // OSA bridges NSUInteger values as strings, including an empty NSMutableArray's count.
            function array(items) {return {count: String(items.length), objectAtIndex: function(i) {return items[i];}};}
            var fixtureSelection = {count: '0', addObject: function(c) {
                if (c !== fixtureCalendar) throw new Error('Wrong calendar'); this.count = String(Number(this.count) + 1);
            }};
            function request() { requests++; status = ${if (granted) 3 else 2}; }
            var store = {
                respondsToSelector: function(s) {return $modern;},
                requestFullAccessToEventsWithCompletion: function(callback) {request();},
                requestAccessToEntityTypeCompletion: function(type, callback) {request();},
                calendarsForEntityType: function(type) {
                    if (status !== 3 || ($status !== 3 && resets !== 1)) throw new Error('Read before permission/reset');
                    return array([fixtureCalendar]);
                },
                predicateForEventsWithStartDateEndDateCalendars: function(start, end, selected) {
                    if (Number(start.timeIntervalSince1970) !== ${args[1]} ||
                        Number(end.timeIntervalSince1970) !== ${args[2]} || Number(selected.count) !== 1) throw new Error('Wrong predicate');
                    return 'fixture predicate';
                },
                eventsMatchingPredicate: function(predicate) {
                    queries++;
                    if (predicate !== 'fixture predicate') throw new Error('Wrong predicate');
                    return array($events);
                }
            };
            Object.defineProperty(store, 'reset', {get: function() {resets++;}});
            var fixtureEventStore = {alloc: {init: store}, authorizationStatusForEntityType: function(type) {return status;}};
            $production
            function run(argv) {
                var result = listFixture(argv);
                if (requests !== ${if (status == 0 || status == 4) 1 else 0} || queries !== 1) throw new Error('Unexpected native work');
                return result;
            }
        """.trimIndent()
    }
}
