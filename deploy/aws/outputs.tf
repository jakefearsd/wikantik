output "instance_id" {
  description = "EC2 instance ID."
  value       = aws_instance.wikantik.id
}

output "eip_public_ip" {
  description = "Elastic IP address — point DNS here if not using var.route53_zone_id."
  value       = aws_eip.wikantik.public_ip
}

output "ssh_command" {
  description = "SSH command (requires var.ssh_key_name to have been set)."
  value       = "ssh ubuntu@${aws_eip.wikantik.public_ip}"
}

output "wikantik_url" {
  description = "Public URL the wiki should be reachable at once cloud-init finishes."
  value       = var.domain != "" ? "https://${var.domain}/" : "http://${aws_eip.wikantik.public_ip}/"
}

output "security_group_id" {
  description = "Security group ID (22/admin_cidr, 80+443/world)."
  value       = aws_security_group.wikantik.id
}

output "data_volume_id" {
  description = "EBS volume ID mounted at /srv/wikantik on the instance."
  value       = aws_ebs_volume.data.id
}

output "dlm_lifecycle_policy_id" {
  description = "DLM lifecycle policy ID managing daily snapshots of the data volume."
  value       = aws_dlm_lifecycle_policy.data_volume.id
}

output "route53_fqdn" {
  description = "FQDN of the created Route53 record, if var.route53_zone_id was set."
  value       = length(aws_route53_record.wikantik) > 0 ? aws_route53_record.wikantik[0].fqdn : null
}

output "ssm_parameter_names" {
  description = "Names (not values) of every SSM SecureString parameter this module created."
  value       = [for p in aws_ssm_parameter.secret : p.name]
}

output "compose_profiles" {
  description = "Compose --profile flags cloud-init activates for the chosen tier + ingress."
  value       = local.compose_profile_args
}
