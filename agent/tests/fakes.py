"""Hand-written EduSoft pages for tests.

The login form copies the real one at
https://edusoftweb.hcmiu.edu.vn/default.aspx?page=dangnhap (checked 2026-09-25):
same field names, same hidden ASP.NET fields.
"""

LOGIN_URL = "https://edusoftweb.hcmiu.edu.vn/default.aspx?page=dangnhap"
HOME_URL = "https://edusoftweb.hcmiu.edu.vn/default.aspx?page=gioithieu"


def page(body):
    return f"""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>EduSoft Web</title></head>
<body>
<form method="post" action="./default.aspx?page=dangnhap" id="Form1">
<div class="aspNetHidden">
<input type="hidden" name="__EVENTTARGET" id="__EVENTTARGET" value="" />
<input type="hidden" name="__EVENTARGUMENT" id="__EVENTARGUMENT" value="" />
<input type="hidden" name="__VIEWSTATE" id="__VIEWSTATE" value="VIEWSTATE-abc123" />
</div>
<div class="aspNetHidden">
<input type="hidden" name="__VIEWSTATEGENERATOR" id="__VIEWSTATEGENERATOR" value="CA0B0334" />
</div>
{body}
</form>
</body></html>"""


LOGIN_BOX = """
<div id="ContentPlaceHolder1_ctl00_pnlDangNhap">
  <span>Tên Đăng Nhập</span>
  <input name="ctl00$ContentPlaceHolder1$ctl00$txtTaiKhoa" type="text" id="ContentPlaceHolder1_ctl00_txtTaiKhoa" class="TextBox" />
  <span>Mật Khẩu</span>
  <input name="ctl00$ContentPlaceHolder1$ctl00$txtMatKhau" type="password" id="ContentPlaceHolder1_ctl00_txtMatKhau" class="TextBox" />
  <input type="submit" name="ctl00$ContentPlaceHolder1$ctl00$btnDangNhap" value="Đăng Nhập" id="ContentPlaceHolder1_ctl00_btnDangNhap" class="DefaultButton" />
  <a id="btnLogin365" href="#">Đăng nhập Office 365</a>
</div>"""

LOGIN_PAGE = page(LOGIN_BOX)

LOGIN_FAILED = page(
    LOGIN_BOX + '<span id="ContentPlaceHolder1_ctl00_lblError" style="color:Red;">'
    "Sai thông tin đăng nhập</span>"
)

CAPTCHA_LOGIN_PAGE = page(
    LOGIN_BOX + '<img src="Captcha.aspx" /><input name="ctl00$ContentPlaceHolder1$ctl00$txtCaptcha" type="text" />'
)

LOGGED_IN_HOME = page(
    '<div id="ContentPlaceHolder1_ctl00_pnlThongTin">Xin chào <b>STUDENT</b>'
    '<a href="default.aspx?page=thoikhoabieu">Thời khóa biểu</a>'
    '<a id="ContentPlaceHolder1_ctl00_lnkDangXuat" href="#">Đăng xuất</a></div>'
)

TIMETABLE_PAGE = page('<table id="ContentPlaceHolder1_ctl00_Table1"><tr><td>IT093IU</td></tr></table>')


# ---- Fake EduSoft client and fake web app, for sync and command tests ----


class FakeEduSoft:
    """Records calls. `pages[section]` is a list of results for read() to return in order
    (an exception instance is raised instead of returned)."""

    def __init__(self, login_error=None, pages=None):
        self.login_error = login_error
        self.pages = pages or {}
        self.logins = []
        self.page_calls = []

    def login(self, student_id, password):
        self.logins.append((student_id, password))
        if self.login_error:
            raise self.login_error

    SECTION_PARTS = {"timetable": ("weekly", "semester"), "exams": ("final", "midterm"),
                     "registration": ("registration",)}

    def get_page(self, name):
        return f"<html>{name}</html>"

    def read(self, name):
        self.page_calls.append(name)
        default = {part: f"<html>{name} {part}</html>" for part in self.SECTION_PARTS[name]}
        if name == "exams":
            default["term"] = "20261"
        results = self.pages.get(name, [default])
        result = results.pop(0) if len(results) > 1 else results[0]
        if isinstance(result, Exception):
            raise result
        return result


class FakeServer:
    def __init__(self, due=True, reason="interval", check_error=None, start_error=None):
        self.due, self.reason, self.check_error, self.start_error = due, reason, check_error, start_error
        self.checks = 0
        self.starts = []
        self.finishes = []

    def check(self):
        from sla_contract.schema import CheckResult

        self.checks += 1
        if self.check_error:
            raise self.check_error
        return CheckResult(due=self.due, reason=self.reason, interval_hours=12)

    def start(self, trigger):
        if self.start_error:
            raise self.start_error
        self.starts.append(trigger)
        return 41

    def finish(self, run_id, result):
        self.finishes.append((run_id, result))
        return result.overall_status()


class FakeIupay:
    """Stands in for IupayClient. `reply` is what read_bills returns (an exception instance is raised instead);
    the default is IUPay's answer for a student with no bills."""

    def __init__(self, reply=None):
        self.reply = reply if reply is not None else {"data": {"data": {"records": []}}}
        self.calls = []

    def read_bills(self, student_id):
        self.calls.append(student_id)
        if isinstance(self.reply, Exception):
            raise self.reply
        return self.reply


class FakeBlackboard:
    def __init__(self, login_error=None):
        self.login_error = login_error
        self.logins = []
        self.logouts = 0
        self.user_id = "_1_1"
        self.capture = None

    def login(self, username, password):
        self.logins.append((username, password))
        if self.login_error:
            raise self.login_error

    def logout(self):
        self.logouts += 1
