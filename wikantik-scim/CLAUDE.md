# wikantik-scim

Module notes, loaded when working under this directory (moved from the root CLAUDE.md).

- **wikantik-scim**: SCIM 2.0 provisioning server at `/scim/v2/*` — bearer-authed `Users` + `Groups` CRUD + discovery (`ServiceProviderConfig`/`Schemas`/`ResourceTypes`) for IdP-driven onboarding/offboarding. User decommission routes through the unified `UserLifecycleService`; Group membership sync routes through `GroupManager`. SCIM Groups never grant the Admin role.
