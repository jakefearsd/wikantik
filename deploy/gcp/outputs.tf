output "instance_id" {
  description = "Compute Engine instance ID."
  value       = google_compute_instance.wikantik.instance_id
}

output "instance_self_link" {
  description = "Compute Engine instance self_link."
  value       = google_compute_instance.wikantik.self_link
}

output "external_ip" {
  description = "Static external IP address — point DNS here if not using var.dns_managed_zone."
  value       = google_compute_address.wikantik.address
}

output "ssh_command" {
  description = "SSH command (requires var.ssh_public_key to have been set)."
  value       = "ssh ubuntu@${google_compute_address.wikantik.address}"
}

output "wikantik_url" {
  description = "Public URL the wiki should be reachable at once cloud-init finishes."
  value       = var.domain != "" ? "https://${var.domain}/" : "http://${google_compute_address.wikantik.address}/"
}

output "firewall_ssh_name" {
  description = "Firewall rule name for 22/admin_cidr."
  value       = google_compute_firewall.ssh.name
}

output "firewall_web_name" {
  description = "Firewall rule name for 80+443/world."
  value       = google_compute_firewall.web.name
}

output "data_disk_id" {
  description = "Persistent disk ID mounted at /srv/wikantik on the instance."
  value       = google_compute_disk.data.id
}

output "snapshot_schedule_policy_id" {
  description = "Resource policy ID managing daily snapshots of the data disk."
  value       = google_compute_resource_policy.snapshot_schedule.id
}

output "dns_record_fqdn" {
  description = "FQDN of the created Cloud DNS record, if var.dns_managed_zone was set."
  value       = length(google_dns_record_set.wikantik) > 0 ? trimsuffix(google_dns_record_set.wikantik[0].name, ".") : null
}

output "secret_ids" {
  description = "Secret Manager secret_ids (not values) this module created."
  value       = [for s in google_secret_manager_secret.secret : s.secret_id]
}

output "service_account_email" {
  description = "Service account email attached to the instance (holds secretAccessor on exactly the secrets this module created)."
  value       = google_service_account.instance.email
}

output "compose_profiles" {
  description = "Compose --profile flags cloud-init activates for the chosen tier + ingress."
  value       = local.compose_profile_args
}
