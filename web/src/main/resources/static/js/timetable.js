// Timetable calendar (FullCalendar 6). The feed sends Vietnam wall-clock times without an
// offset; with timeZone "UTC" the calendar shows them exactly as sent, on any device.
document.addEventListener("DOMContentLoaded", function () {
  var element = document.getElementById("calendar");
  if (!element || !window.FullCalendar) {
    return;
  }
  var VIETNAM_OFFSET_MS = 7 * 60 * 60 * 1000;
  var narrow = window.matchMedia("(max-width: 700px)").matches;
  var clock = { hour: "2-digit", minute: "2-digit", hour12: false };

  function textElement(tag, className, text) {
    var node = document.createElement(tag);
    node.className = className;
    node.textContent = text; // course names and rooms come from EduSoft: never insert them as HTML
    return node;
  }

  // Dates the Vietnamese way (day/month). FullCalendar hands formatters {year, month (0-11), day, marker}.
  var WEEKDAYS = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
  var WEEKDAYS_LONG = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  function pad(n) {
    return (n < 10 ? "0" : "") + n;
  }
  function dayMonth(d) {
    return pad(d.day) + "/" + pad(d.month + 1);
  }
  function fullDate(d) {
    return dayMonth(d) + "/" + d.year;
  }
  function weekTitle(arg) {
    return dayMonth(arg.start) + " – " + fullDate(arg.end || arg.start);
  }
  function dayTitle(arg) {
    return WEEKDAYS_LONG[arg.date.marker.getUTCDay()] + " " + fullDate(arg.date);
  }

  function roomLabel(room) {
    if (!room) {
      return "";
    }
    return room.toUpperCase().indexOf("ONLINE") === 0 ? "Online" : room;
  }

  var calendar = new FullCalendar.Calendar(element, {
    timeZone: "UTC",
    now: function () {
      return new Date(Date.now() + VIETNAM_OFFSET_MS); // "now" in Vietnam, for the red now-line
    },
    initialView: narrow ? "listWeek" : "timeGridWeek",
    firstDay: 1,
    headerToolbar: narrow
      ? { left: "prev,next today", center: "", right: "listWeek,timeGridDay,dayGridMonth" }
      : { left: "prev,next today", center: "title", right: "dayGridMonth,timeGridWeek,timeGridDay,listWeek" },
    footerToolbar: narrow ? { center: "title" } : false,
    buttonText: { today: "Today", month: "Month", week: "Week", day: "Day", list: "List" },
    slotMinTime: "07:00:00",
    slotMaxTime: "19:00:00",
    allDaySlot: true,
    allDayText: "All day",
    nowIndicator: true,
    height: "auto",
    slotLabelFormat: clock,
    eventTimeFormat: clock,
    noEventsText: "No classes or exams in this period.",
    views: {
      timeGridWeek: { titleFormat: weekTitle },
      listWeek: { titleFormat: weekTitle },
      timeGridDay: { titleFormat: dayTitle },
    },
    dayHeaderContent: function (arg) {
      var name = WEEKDAYS[arg.date.getUTCDay()];
      if (arg.view.type === "dayGridMonth") {
        return name;
      }
      return name + " " + pad(arg.date.getUTCDate()) + "/" + pad(arg.date.getUTCMonth() + 1);
    },
    listDayFormat: function (arg) {
      return WEEKDAYS_LONG[arg.date.marker.getUTCDay()];
    },
    listDaySideFormat: function (arg) {
      return fullDate(arg.date);
    },
    events: element.dataset.feed,
    eventContent: function (arg) {
      var room = roomLabel(arg.event.extendedProps.room);
      var linkInList = arg.event.url && arg.view.type.indexOf("list") === 0;
      var title = textElement(linkInList ? "a" : "div", "event-title", arg.event.title);
      if (linkInList) {
        title.href = arg.event.url; // FullCalendar's list rows follow the link inside the row
      }
      var nodes = [
        textElement("div", "event-time", arg.timeText),
        title,
      ];
      if (room) {
        nodes.push(textElement("div", "event-room", room));
      }
      return { domNodes: nodes };
    },
  });
  calendar.render();
});
