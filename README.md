# dcre-hcs

Holiday Calendar Sync (R-38). Single writer of public_holiday (R-04): syncs ZA public holidays from Nager.Date (current + next year per country) via idempotent INSERT ... ON CONFLICT upserts. Clock-launched every 6h by AGT (identifying params sync.date + window). 3-tier: SyncTasklet -> HolidaySyncService -> NagerClient + data/repo. Non-2xx from Nager fails the job (level-triggered, next window self-heals).
