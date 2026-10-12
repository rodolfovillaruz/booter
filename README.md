# Booter

A personal Android app for booting EC2 instances.

- **Settings**: enter your AWS access key ID, secret access key, and region. The keys are encrypted
  on-device with an Android Keystore AES-256-GCM key that can only be used after a fingerprint scan.
  If you add or remove a fingerprint, that key is invalidated and you'll need to enter the AWS keys again.
- **Namespace** (optional, in Settings): when set, the list only shows instances tagged
  `booter:namespace` with that value, and every instance you launch gets that tag. Leave it blank to
  see every instance; launches then go untagged. The namespace is shown under the region in the list.
- **Main list**: shows every non-terminated instance in the region.
- **Start** (the button, or swipe an entry **left**): pick `t3.micro` or `t3.large` every time. If the
  size you pick is different, Booter changes the instance type first and then starts it.
- **Resize** (swipe a **running** entry **right**): pick the other size. Booter then tells you to shut
  the instance down. Once the instance reports `stopped`, Booter changes the type and starts it
  again. This only happens while the app is open and unlocked; a pending resize survives restarts.
- **Terminate** (the **⋮** menu on an entry): permanently deletes the instance, along with its root
  disk and any other disk set to delete on termination. It asks for your fingerprint to confirm. AWS
  refuses if the instance has termination protection on.
- **Launch** (the button at the bottom right): name a new instance and pick its image, size, key
  pair, subnet, and security group. The images (the latest Amazon Linux 2023 and Ubuntu 24.04, plus
  your own x86_64 AMIs), key pairs, subnets, and security groups are loaded live from the region, so
  it works without a default VPC. The default VPC's subnets come first when there is one. Only the
  chosen subnet's VPC's groups are offered; each shows whether it allows SSH, and the first one that
  does is preselected. If none does, `default` is preselected, but it only allows traffic from
  itself, so you can't SSH in. The instance always gets a public IP (it still needs a subnet
  routed to an internet gateway to be reachable) and the image's default disk. Shutting it down
  from inside the OS stops it rather than terminating it.

## Build

Open this folder in Android Studio and let it sync. It uses the Gradle version set in
`gradle/wrapper/gradle-wrapper.properties`. Then run the `app` configuration on a device that has a
fingerprint enrolled. minSdk is 28.

## IAM policy for the keys

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": [
      "ec2:DescribeInstances",
      "ec2:DescribeInstanceAttribute",
      "ec2:StartInstances",
      "ec2:TerminateInstances",
      "ec2:ModifyInstanceAttribute",
      "ec2:RunInstances",
      "ec2:CreateTags",
      "ec2:DescribeImages",
      "ec2:DescribeKeyPairs",
      "ec2:DescribeSubnets",
      "ec2:DescribeSecurityGroups"
    ],
    "Resource": "*"
  }]
}
```
