"""Page readers: EduSoft pages -> the shared data format.

Each reader takes the dict of pages that EduSoftClient.read(section) returns. IUPay's reader,
parsers/iupay.py, takes IUPay's JSON answer instead.
"""

from sla_agent.parsers.exams import parse_exams
from sla_agent.parsers.timetable import parse_timetable


PARSERS = {"timetable": parse_timetable, "exams": parse_exams}
