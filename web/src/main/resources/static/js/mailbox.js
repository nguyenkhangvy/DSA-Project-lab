/*
 * Mailbox (docs/superpowers/specs/2026-09-28-mailbox-events-design.md, 4.3). Opening an email from its subject or
 * "Web ↗" tells the site, which marks the card opened (and Done when auto-Done is on); the link opens as usual,
 * without waiting. Changing the auto-Done tick box saves it at once.
 */
document.addEventListener("DOMContentLoaded", function () {
  var mailbox = document.querySelector(".mailbox");
  if (!mailbox) {
    return;
  }

  mailbox.querySelectorAll("a[data-opened]").forEach(function (link) {
    link.addEventListener("click", function () {
      var row = link.closest(".mail-row");
      var headers = {};
      headers[mailbox.dataset.csrfHeader] = mailbox.dataset.csrf;
      fetch(link.dataset.opened, { method: "POST", headers: headers, credentials: "same-origin", keepalive: true })
        .then(function (answer) {
          return answer.ok ? answer.json() : null;
        })
        .then(function (result) {
          if (result && row) {
            row.classList.add("is-opened");
            row.classList.toggle("is-done", result.done);
          }
        })
        .catch(function () {
          // Nothing is recorded; the student can still press Done.
        });
    });
  });

  var autoDone = document.getElementById("auto-done");
  if (autoDone) {
    autoDone.addEventListener("change", function () {
      autoDone.form.submit();
    });
  }
});
