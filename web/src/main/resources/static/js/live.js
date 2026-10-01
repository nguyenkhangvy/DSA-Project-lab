/*
 * Mailbox and Overview update themselves (docs/superpowers/specs/2026-10-01-live-sync-design.md, 4.3). Every 30 s,
 * while the tab is visible, ask the site for the newest finished sync; when it is newer than this page, fetch the
 * page again and swap each [data-live] area, except one where the student is typing or choosing (a focused input,
 * select or text box): it waits for the next round, and "Updated" shows once every area is up to date. A focused
 * link or button doesn't hold an area back (a click focuses it). Lists the student opened (<details>) stay open.
 * If the areas differ (e.g. Outlook got connected), the whole page reloads. Failures are silent: tried again later.
 */
document.addEventListener("DOMContentLoaded", function () {
  var main = document.querySelector("main[data-version]");
  if (!main) {
    return;
  }
  var EVERY_MS = 30000;
  var VIETNAM_OFFSET_MS = 7 * 60 * 60 * 1000;
  var EDITING = "input, select, textarea, [contenteditable]";
  var note = document.createElement("p");
  note.className = "live-note";
  note.setAttribute("role", "status");
  note.hidden = true;
  document.body.appendChild(note);

  function names(root) {
    return Array.prototype.map.call(root.querySelectorAll("[data-live]"), function (area) {
      return area.dataset.live;
    }).sort().join(",");
  }
  function pad(n) {
    return (n < 10 ? "0" : "") + n;
  }
  function clock() {
    var vietnam = new Date(Date.now() + VIETNAM_OFFSET_MS);
    return pad(vietnam.getUTCHours()) + ":" + pad(vietnam.getUTCMinutes());
  }
  function editingIn(area) {
    var focused = document.activeElement;
    return focused && focused.matches && focused.matches(EDITING) && area.contains(focused);
  }
  // A <details> is known by the nearest element with an id around it inside the area, and its place there.
  function detailsKey(details, area) {
    var holder = details.parentElement.closest("[id]");
    var scope = holder && area.contains(holder) ? holder : area;
    var place = Array.prototype.indexOf.call(scope.querySelectorAll("details"), details);
    return (scope === area ? "" : scope.id) + "#" + place;
  }
  function swap(area, fresh) {
    var open = Array.prototype.map.call(area.querySelectorAll("details[open]"), function (details) {
      return detailsKey(details, area);
    });
    area.innerHTML = fresh.innerHTML;
    area.querySelectorAll("details").forEach(function (details) {
      if (open.indexOf(detailsKey(details, area)) >= 0) {
        details.open = true;
      }
    });
  }
  function refresh() {
    return fetch(location.pathname + location.search, { credentials: "same-origin" })
      .then(function (answer) {
        return answer.ok ? answer.text() : null;
      })
      .then(function (html) {
        var fresh = html && new DOMParser().parseFromString(html, "text/html");
        var freshMain = fresh && fresh.querySelector("main[data-version]");
        if (!freshMain) {
          return; // e.g. the login page after the session ended
        }
        if (names(fresh) !== names(document)) {
          location.reload();
          return;
        }
        var waiting = false;
        document.querySelectorAll("[data-live]").forEach(function (area) {
          if (editingIn(area)) {
            waiting = true;
            return;
          }
          swap(area, fresh.querySelector('[data-live="' + area.dataset.live + '"]'));
        });
        if (waiting) {
          return; // the version stays, so the next round tries the waiting area again
        }
        main.dataset.version = freshMain.dataset.version;
        note.textContent = "Updated " + clock();
        note.hidden = false;
        setTimeout(function () {
          note.hidden = true;
        }, 5000);
      });
  }
  function check() {
    if (document.visibilityState !== "visible") {
      return;
    }
    fetch("/school/api/version", { credentials: "same-origin", headers: { Accept: "application/json" } })
      .then(function (answer) {
        var json = answer.ok && (answer.headers.get("Content-Type") || "").indexOf("json") >= 0;
        return json ? answer.json() : null;
      })
      .then(function (answer) {
        if (answer && typeof answer.version === "number" && answer.version > Number(main.dataset.version)) {
          return refresh();
        }
      })
      .catch(function () {
        // tried again in 30 seconds
      });
  }
  setInterval(check, EVERY_MS);
});
