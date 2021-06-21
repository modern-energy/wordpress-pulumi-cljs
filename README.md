# wordpress-pulumi-cljs

Pulumi stack demonstrating the use of
[pulumi-cljs](http://github.com/modern-energy/pulumi-cljs) to deploy
Wordpress to AWS.

## Usage

1. Create a Pulumi stack, and populate the config file (using
`Pulumi.example.dev` as a template.)

1. Run `shadow-cljs compile pulumi` to compile the ClojureScript code
to JavaScript.

1. Then, run `pulumi up` to deploy the stack.

## Configuration

See `Pulumi.example.yml` for a config file template.

Keys:

- `vpc`: ID of the AWS VPC in which to run.
- `protect`: Whether to allow deletion of resources. Set to `true` for
production, `false` for dev.
- `zone`: Domain name of a pre-existing AWS Route53 Hosted Zone to use for DNS.
- `subdomain`: Subdomain to register routes for on the zone. May be
omitted to run on the base zone domain.
- `certificate-arn`: ARN of a pre-existing certificate provisioned in ACM, which must cover the domain & subdomains specified by the given Zone. If you intend to use WordPress Multisite, must be a wildcard cert to cover all relevant domains.
- `private-subnets`: list of pre-existing subnets IDs in which to run the database endpoints and Wordpress ECS tasks. For security reasons, these should not be reachable via the public internet. You must provide at least 2, which is required by RDS.
- `public-subnets`: list of pre-existing subnet IDs in which to run Application Load Balancer listeners. These must be reachable from the public internet. There must be at least two, as required for an ALB Listener.
- `ingress-cidrs `: list of network CIDRs allowed to access the
  application endpoint. Should be set to `"0.0.0.0/0"` for production
  on the public internet, and to developer IPs only during development
  and testing (e.g, until Wordpress can be configured & secured correctly.
- `efs-ingress-cidrs`: List of Network CIDRs allowed to mount the EFS
  filesystem, in case that is needed for low-level configuration of
  WordPress. Note that since the EFS filesystem is configured to run
  on the "private" subnets, these CIDRs must be in your VPCs address
  block and represent either a jumphost or VPN endpoints -- it is
  never possible to access the EFS filesystem from the public internet.
- `wordpress-env`: map of environment variables that will be passed to
  the WordPress process. This image is based on the
  [official Wordpress image](https://hub.docker.com/_/wordpress/), see
  its documentation for a list of available settings.

# Notes

### Cost

This is not a particularly cost-effective way to deploy
WordPress relative to hosted options, unless you intend to reach very
high volume. For a single service node and the load balancer, this
stack costs around $35/month, plus RDS Aurora Serverless charges which
could amount up to ~$90/month depending on utilization.

### Image

This deployment uses a custom WordPress image defined in the `/image`
directory. This is a very lightweight extension of the official
WordPress image -- all it does is add a health-check endpoint that
exercises Apache and the PHP runtime, but deliberately does _not_
establish or test a database connection. This allows the RDS Aurora
Serverless cluster to "scale to zero", drastically decreasing costs.

Note that this means the Wordpress will also exhibit "cold starts",
with latencies of 10-15 seconds if it has not been accessed recently.
