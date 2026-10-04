# Booter

A personal Android app for booting EC2 instances.

- **Settings**: enter your AWS access key ID, secret access key, and region. The keys are encrypted
  on-device with an Android Keystore AES-256-GCM key that can only be used after a fingerprint scan.
  If you add or remove a fingerprint, that key is invalidated and you'll need to enter the AWS keys again.
- **Main list**: shows every non-terminated instance in the region. **Tap** a running instance to open
  it in [SSHBorg](https://sshborg.com); **long-press** to copy its public IP.
- **SSHBorg**: the first tap on an instance opens SSHBorg's new-host form, prefilled with the instance
  name and IP; add the username and key and save, and it connects. SSHBorg links that host to the
  instance ID, so later taps connect straight away and update the IP, which changes on every start
  without an Elastic IP. An existing SSHBorg host already pointing at the instance's current IP gets
  linked automatically.
- **Start** (the button, or swipe an entry **left**): pick `t3.micro` or `t3.large` every time. If the
  size you pick is different, Booter changes the instance type first and then starts it.
- **Resize** (swipe a **running** entry **right**): pick the other size. Booter then tells you to shut
  the instance down. Once the instance reports `stopped`, Booter changes the type and starts it
  again. This only happens while the app is open and unlocked; a pending resize survives restarts.
- **Launch** (the button at the bottom right): name a new instance and pick its image, size, key
  pair, subnet, and security group. The images (the latest Amazon Linux 2023 and Ubuntu 24.04, plus
  your own x86_64 AMIs), key pairs, subnets, and security groups are loaded live from the region, so
  it works without a default VPC. The default VPC's subnets come first when there is one. Only the
  chosen subnet's VPC's groups are offered; each shows whether it allows SSH, and the first one that
  does is preselected. If none does, `default` is preselected, but it only allows traffic from
  itself, so SSHBorg can't connect. The instance always gets a public IP (it still needs a subnet
  routed to an internet gateway to be reachable) and the image's default disk. Shutting it down
  from inside the OS stops it rather than terminating it.
- **Key from SSHBorg**: the key pair list ends with **Use a key from SSHBorg…**, which opens SSHBorg's
  key list. Only the public half of the key you pick comes back. Booter looks for an AWS key pair
  that already holds it, matched by fingerprint or public key, and selects it. If there isn't one,
  Booter imports it, named after the key's SSHBorg label. If AWS already has a different key under
  that name, Booter asks you for another name. AWS only accepts RSA and ED25519 keys.

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
      "ec2:ModifyInstanceAttribute",
      "ec2:RunInstances",
      "ec2:CreateTags",
      "ec2:DescribeImages",
      "ec2:DescribeKeyPairs",
      "ec2:ImportKeyPair",
      "ec2:DescribeSubnets",
      "ec2:DescribeSecurityGroups"
    ],
    "Resource": "*"
  }]
}
```
