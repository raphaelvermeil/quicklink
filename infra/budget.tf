# The one resource here that protects the person rather than the system.
#
# This stack's steady-state cost is cents, but the upload endpoint is public and
# unauthenticated: anyone who finds it can put objects in the originals bucket. The
# lifecycle rule bounds how long that costs, and this bounds how long it goes unnoticed.
resource "aws_budgets_budget" "monthly" {
  name         = "${var.project}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  # Forecast, not actual: being told at 80% of the month that the month is lost is
  # not an alert, it is a receipt.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = [var.budget_notification_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.budget_notification_email]
  }
}
