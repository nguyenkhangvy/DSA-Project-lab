// Event form: show "N" and "Last day" unless the event happens once, and the weekdays only for "every N weeks";
// ask before Delete. Without JavaScript every field stays visible and the form still works.
document.addEventListener("DOMContentLoaded", function () {
  var form = document.querySelector("form.event-form");
  if (form) {
    var update = function () {
      var checked = form.querySelector("input[name=repeat]:checked");
      var repeat = checked ? checked.value : "once";
      form.querySelectorAll("[data-show-for]").forEach(function (element) {
        element.hidden = element.getAttribute("data-show-for").split(" ").indexOf(repeat) < 0;
      });
    };
    form.addEventListener("change", update);
    update();
  }
  var remove = document.querySelector("form.event-delete");
  if (remove) {
    remove.addEventListener("submit", function (event) {
      if (!window.confirm("Delete this event and all its days?")) {
        event.preventDefault();
      }
    });
  }
});
