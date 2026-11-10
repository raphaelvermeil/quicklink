terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # State is local, which is right for a one-person project and wrong for a team: two
  # people applying at once would clobber each other's state file. The team answer is
  # an S3 backend with DynamoDB (or S3 native) locking — deliberately left out here
  # because bootstrapping a backend bucket before the backend exists is its own chore.
  #
  # backend "s3" {
  #   bucket       = "quicklink-tfstate-<account-id>"
  #   key          = "quicklink/terraform.tfstate"
  #   region       = "eu-west-3"
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = var.project
      ManagedBy = "terraform"
    }
  }
}
