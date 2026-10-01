/*
 * Mailbox (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.3). Opening an email from its subject or
 * "Web ↗" tells the site, which marks the card opened (and Done when auto-Done is on); the link opens as usual,
 * without waiting. The row turns grey at once; the site's answer only says whether it became Done. A click (also
 * with Ctrl, Shift or ⌘, or Enter on the keyboard) and a middle-click count. "Open in new tab" from the right-click
 * menu can't be seen by a page, so it records nothing. Changing the auto-Done tick box saves it at once. Links are
 * handled on the document, so rows that live.js replaces keep working.
 */
document.addEventListener("DOMContentLoaded", function () {
  var mailbox = document.querySelector(".mailbox");
  if (!mailbox) {
    return;
  }

  function opened(link) {
    var row = link.closest(".mail-row");
    if (row) {
      row.classList.add("is-opened");
    }
    var headers = {};
    headers[mailbox.dataset.csrfHeader] = mailbox.dataset.csrf;
    fetch(link.dataset.opened, { method: "POST", headers: headers, credentials: "same-origin", keepalive: true })
      .then(function (answer) {
        return answer.ok ? answer.json() : null;
      })
      .then(function (result) {
        if (result && row) {
          row.classList.toggle("is-done", result.done);
        }
      })
      .catch(function () {
        // Nothing is recorded; the student can still press Done.
      });
  }

  function openedLink(event) {
    var link = event.target.closest && event.target.closest("a[data-opened]");
    return link && mailbox.contains(link) ? link : null;
  }
  document.addEventListener("click", function (event) {
    var link = openedLink(event);
    if (link) {
      opened(link);
    }
  });
  document.addEventListener("auxclick", function (event) {
    var link = openedLink(event);
    if (link && event.button === 1) {
      opened(link);
    }
  });

  var autoDone = document.getElementById("auto-done");
  if (autoDone) {
    autoDone.addEventListener("change", function () {
      autoDone.form.submit();
    });
  }
});
