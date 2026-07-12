package za.co.fnb.dcre.hcs.data.repo;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import za.co.fnb.dcre.hcs.data.model.PublicHolidayEntity;

import java.time.LocalDate;
import java.util.UUID;

public interface PublicHolidayRepo extends CrudRepository<PublicHolidayEntity, UUID> {

    /** Idempotent upsert on the business identity (never CRDB UPSERT INTO). */
    @Modifying
    @Query("""
            INSERT INTO public_holiday (id, country, holiday_date, local_name, name, is_global)
            VALUES (gen_random_uuid(), :country, :date, :localName, :name, :global)
            ON CONFLICT (country, holiday_date) DO UPDATE SET
                local_name = excluded.local_name,
                name = excluded.name,
                is_global = excluded.is_global,
                updated_at = now()""")
    void upsert(@Param("country") String country, @Param("date") LocalDate date,
                @Param("localName") String localName, @Param("name") String name,
                @Param("global") boolean global);

    long countByCountry(String country);
}
