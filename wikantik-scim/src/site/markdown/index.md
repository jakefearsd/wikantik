# Wikantik SCIM 2.0 Provisioning Server

SCIM 2.0 provisioning surface at `/scim/v2/*`: bearer-authed `Users` and
`Groups` CRUD plus discovery (`ServiceProviderConfig`, `Schemas`,
`ResourceTypes`) for identity-provider-driven onboarding and offboarding.

User decommissioning routes through the shared `UserLifecycleService` and
group membership sync routes through `GroupManager`, so SCIM-driven changes
follow the same lifecycle rules as changes made through the admin UI. SCIM
groups can never grant the Admin role.
