package za.co.fnb.dcre.hcs.data.model;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import za.co.fnb.dcre.platform.persistence.BaseEntity;

import java.time.LocalDate;

@Table("public_holiday")
public class PublicHolidayEntity extends BaseEntity {

    private String country;
    private LocalDate holidayDate;
    private String localName;
    private String name;
    @Column("is_global")
    private Boolean isGlobal;

    public String getCountry() { return country; }
    public LocalDate getHolidayDate() { return holidayDate; }
    public String getLocalName() { return localName; }
    public String getName() { return name; }
    public Boolean getIsGlobal() { return isGlobal; }
}
