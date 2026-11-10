variable "aws_region" {
  description = "Region for every resource here. Pick the one nearest the VM running the app: each upload crosses this link twice."
  type        = string
  default     = "eu-west-3"
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
