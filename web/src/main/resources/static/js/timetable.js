// Timetable calendar (FullCalendar 6). The feed sends Vietnam wall-clock times without an
// offset; with timeZone "UTC" the calendar shows them exactly as sent, on any device.
document.addEventListener("DOMContentLoaded", function () {
  var element = document.getElementById("calendar");
  if (!element || !window.FullCalendar) {
    return;
  }
  var VIETNAM_OFFSET_MS = 7 * 60 * 60 * 1000;
  // Week and day views show 07:00-23:00; "Show more" adds the night, and this browser remembers the choice.
  var DAY_START = "07:00:00";
  var DAY_END = "23:00:00";
  var DAY_START_MINUTES = 7 * 60;
  var DAY_END_MINUTES = 23 * 60;
  var TIME_GRID_HEIGHT = 760; // pixels, when the whole day shows: about 07:00-19:00 at once, scroll for the rest
  var WHOLE_DAY_KEY = "sla.timetable.wholeDay";
  function remembered() {
    try {
      return window.localStorage.getItem(WHOLE_DAY_KEY) === "1";
    } catch (error) {
      return false; // storage blocked (a private window): start with the usual hours
    }
  }
  function remember(value) {
    try {
      window.localStorage.setItem(WHOLE_DAY_KEY, value ? "1" : "0");
    } catch (error) {
      // storage blocked: the choice lasts until the page is left
    }
  }
  var wholeDay = remembered();
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

  function isTimeGrid() {
    return calendar.view.type.indexOf("timeGrid") === 0;
  }
  function minutes(date) {
    return date.getUTCHours() * 60 + date.getUTCMinutes();
  }
  // Timed events in the dates shown that start before 07:00 or end after 23:00 (or on a later day).
  function hiddenCount() {
    var view = calendar.view;
    return calendar.getEvents().filter(function (event) {
      var end = event.end || event.start;
      if (event.allDay || !event.start || event.start >= view.activeEnd || end <= view.activeStart) {
        return false;
      }
      var endsLaterDay = end.getUTCDate() !== event.start.getUTCDate(); // 24:00 is the next day's 00:00
      return minutes(event.start) < DAY_START_MINUTES || endsLaterDay || minutes(end) > DAY_END_MINUTES;
    }).length;
  }
  // The button's words, and only in the week and day views (month and list views show every hour).
  function label() {
    var button = element.querySelector(".fc-wholeDay-button");
    if (!button) {
      return;
    }
    var hidden = wholeDay ? 0 : hiddenCount();
    button.textContent = wholeDay ? "Show less" : hidden > 0 ? "Show more (" + hidden + " hidden)" : "Show more";
    button.hidden = !isTimeGrid();
  }
  function setIfChanged(name, value) {
    if (calendar.getOption(name) !== value) {
      calendar.setOption(name, value);
    }
  }
  function apply() {
    setIfChanged("slotMinTime", wholeDay ? "00:00:00" : DAY_START);
    setIfChanged("slotMaxTime", wholeDay ? "24:00:00" : DAY_END);
    setIfChanged("height", wholeDay && isTimeGrid() ? TIME_GRID_HEIGHT : "auto");
    label();
  }

  var calendar = new FullCalendar.Calendar(element, {
    timeZone: "UTC",
    now: function () {
      return new Date(Date.now() + VIETNAM_OFFSET_MS); // "now" in Vietnam, for the red now-line
    },
    initialView: narrow ? "listWeek" : "timeGridWeek",
    firstDay: 1,
    headerToolbar: narrow
      ? { left: "prev,next today wholeDay", center: "", right: "listWeek,timeGridDay,dayGridMonth" }
      : { left: "prev,next today wholeDay", center: "title", right: "dayGridMonth,timeGridWeek,timeGridDay,listWeek" },
    customButtons: {
      wholeDay: {
        text: "Show more",
        hint: "Show or hide 23:00-07:00",
        click: function () {
          wholeDay = !wholeDay;
          remember(wholeDay);
          apply();
          if (wholeDay) {
            calendar.scrollToTime(DAY_START);
          }
        },
      },
    },
    footerToolbar: narrow ? { center: "title" } : false,
    buttonText: { today: "Today", month: "Month", week: "Week", day: "Day", list: "List" },
    slotMinTime: wholeDay ? "00:00:00" : DAY_START,
    slotMaxTime: wholeDay ? "24:00:00" : DAY_END,
    scrollTime: DAY_START, // the whole day opens at 07:00
    allDaySlot: true,
    allDayText: "All day",
    nowIndicator: true,
    height: "auto",
    slotLabelFormat: clock,
    eventTimeFormat: clock,
    noEventsText: "No classes, exams or events in this period.",
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
    datesSet: apply,
    eventsSet: label,
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
