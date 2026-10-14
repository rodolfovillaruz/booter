# Booter

A personal Android app for booting EC2 instances.

- **Settings**: enter your AWS access key ID, secret access key, and region. The keys are encrypted
  on-device with an Android Keystore AES-256-GCM key that can only be used after a fingerprint scan.
  If you add or remove a fingerprint, that key is invalidated and you'll need to enter the AWS keys again.
- **Namespace** (optional, in Settings): when set, the list only shows instances tagged
  `booter:namespace` with that value, and every instance you launch gets that tag. Leave it blank to
  see every instance; launches then go untagged. The namespace is shown under the region in the list.
- **Extra SSH keys** (optional, in Settings): OpenSSH public keys, one per line. AWS allows only one
  key pair per instance, so Booter passes these as cloud-config user data and cloud-init adds them to
  the default user's `authorized_keys` (`ec2-user`, `ubuntu`, ...) on first boot, next to the key pair.
  This only happens on first boot: to change the keys later, edit `authorized_keys` on the instance.
  The image needs cloud-init, which both stock images and most Linux AMIs have.
- **Main list**: shows every non-terminated instance in the region. **Tap** an instance to copy its
  public IP, then paste it into whichever SSH client you like.
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
  from inside the OS stops it rather than terminating it. When extra SSH keys are saved, a
  checkbox (on by default) adds them to the launch.

## Install

Signed APKs are attached to each [GitHub release](https://github.com/rodolfovillaruz/booter/releases).
To get updates automatically, add `https://github.com/rodolfovillaruz/booter` to
[Obtainium](https://github.com/ImranR98/Obtainium). Every release is signed with the same key, so a
copy installed from Android Studio or signed with another key has to be uninstalled once first.

To cut a release, bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, and push a
matching tag (`v` + `versionName`, e.g. `v0.2.0`). The release workflow refuses a tag that doesn't
match `versionName` or a `versionCode` that isn't higher than the previous release's.

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

## License

Booter is free software, released under the [GNU General Public License v3.0](LICENSE).
