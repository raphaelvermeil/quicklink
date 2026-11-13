variable "aws_region" {
  description = <<-EOT
    Region for every resource here. Pick the one nearest the VM running the app: each
    upload crosses this link twice.

    Defaults to us-east-2 because the account this was deployed from sits under an
    organization SCP that permits only that region — anything else fails with "explicit
    deny in a service control policy", which reads like a credentials problem and is not.
  EOT
  type        = string
  default     = "us-east-2"
}

variable "project" {
  description = "Name prefix for every resource, so one account can hold more than one copy."
  type        = string
  default     = "quicklink"
}

variable "budget_notification_email" {
  description = <<-EOT
    Where to send the billing alert. Required on purpose: this stack is cheap but not
    free, and an unauthenticated public upload endpoint is exactly the kind of thing
    that turns into a surprise bill. No default, so it cannot be forgotten.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.budget_notification_email))
    error_message = "budget_notification_email must be an email address."
  }
}

variable "monthly_budget_usd" {
  description = "Alert threshold. Normal running cost for this project is cents; a dollar means something is wrong."
  type        = number
  default     = 1
}

variable "original_retention_days" {
  description = "Originals are only needed until the worker has compressed them. Deleting them later keeps storage from growing forever."
  type        = number
  default     = 30
}
