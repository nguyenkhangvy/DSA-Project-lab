"""Opening the website like an app (app_window.py): Microsoft Edge's app mode, or else the default browser. Nothing
is really started here."""

import sys

import pytest

from sla_agent import app_window


class Calls(list):
    def __call__(self, *args):
        self.append(args)


def test_the_website_opens_in_an_edge_app_window():
    started = Calls()

    app_window.open_app("https://sla.example.com", find_edge=lambda: r"C:\Edge\msedge.exe", start=started,
                        fallback=pytest.fail)

    assert started == [([r"C:\Edge\msedge.exe", "--app=https://sla.example.com/"],)]


def test_without_edge_the_default_browser_opens_it():
    opened = Calls()

    app_window.open_app("http://localhost:5000/", find_edge=lambda: None, start=pytest.fail, fallback=opened)

    assert opened == [("http://localhost:5000/",)]


def test_when_edge_cannot_start_the_default_browser_opens_it():
    def broken(command):
        raise OSError("The system cannot find the file specified")

    opened = Calls()

    app_window.open_app("https://sla.example.com", find_edge=lambda: r"C:\Edge\msedge.exe", start=broken,
                        fallback=opened)

    assert opened == [("https://sla.example.com/",)]


@pytest.mark.skipif(sys.platform != "win32", reason="Windows' own record of where Edge is")
def test_edge_is_found_where_windows_records_it():
    assert app_window.edge().lower().endswith("msedge.exe")
