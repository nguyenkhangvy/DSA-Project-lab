/*
 * Mailbox and Overview update themselves (docs/superpowers/specs/2026-10-01-live-sync-design.md, 4.3). Every 30 s,
 * while the tab is visible, ask the site for the newest finished sync; when it is newer than this page, fetch the
 * page again and swap each [data-live] area, except one holding the focused element (it waits for the next round).
 * If the areas differ (e.g. Outlook got connected), the whole page reloads. Failures are silent: tried again later.
 */
document.addEventListener("DOMContentLoaded", function () {
  var main = document.querySelector("main[data-version]");
  if (!main) {
    return;
  }
  var EVERY_MS = 30000;
  var VIETNAM_OFFSET_MS = 7 * 60 * 60 * 1000;
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
  function focusedIn(area) {
    var focused = document.activeElement;
    return focused && focused !== document.body && area.contains(focused);
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
          if (focusedIn(area)) {
            waiting = true;
            return;
          }
          area.innerHTML = fresh.querySelector('[data-live="' + area.dataset.live + '"]').innerHTML;
        });
        if (!waiting) {
          main.dataset.version = freshMain.dataset.version;
        }
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
