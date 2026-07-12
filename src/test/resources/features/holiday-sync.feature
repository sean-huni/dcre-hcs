@hcs
Feature: HCS public holiday calendar sync from Nager.Date

  HCS is the single writer of the public holiday calendar (R-04). Each 6h
  window fetches the base year plus the next year per country, upserting on
  (country, holiday_date) so a resync is idempotent and self-correcting.
  A failing upstream fails the job; the next window retries (R-38).

  Scenario: A sync run persists holidays for the current and next year
    Given the Nager API serves the standard ZA fixtures
    When the holiday sync runs for date "2026-07-12"
    Then the HCS job completes
    And 3 ZA public holidays are stored
    And the ZA holiday on "2026-12-25" is named "Christmas Day"
    And the ZA holiday on "2027-01-01" is stored

  Scenario: A resync is idempotent and refreshes updated fields
    Given the Nager API serves the standard ZA fixtures
    And a completed holiday sync for date "2026-07-12"
    And the Nager API now serves local name "Nuwejaarsdag (hersien)" for "2026-01-01"
    When the holiday sync runs for date "2026-07-12"
    Then the HCS job completes
    And 3 ZA public holidays are stored
    And the ZA holiday on "2026-01-01" has local name "Nuwejaarsdag (hersien)"

  Scenario: An upstream 500 fails the job and leaves existing rows untouched
    Given the Nager API serves the standard ZA fixtures
    And a completed holiday sync for date "2026-07-12"
    And the Nager API starts returning 500
    When the holiday sync runs for date "2026-07-12"
    Then the HCS job fails
    And 3 ZA public holidays are stored
    And the ZA holiday on "2026-12-25" is named "Christmas Day"
