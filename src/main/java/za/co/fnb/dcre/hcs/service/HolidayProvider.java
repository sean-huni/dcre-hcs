package za.co.fnb.dcre.hcs.service;

import java.time.LocalDate;
import java.util.List;

/** Public-holiday source abstraction: Nager.Date primary, Calendarific-compatible fallback. */
public interface HolidayProvider {

    List<Holiday> fetch(int year, String country);

    /** Provider-neutral holiday shape, matching the public_holiday columns. */
    record Holiday(LocalDate date, String localName, String name, boolean global) {
    }
}
