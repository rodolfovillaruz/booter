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
      "ec2:ModifyInstanceAttribute"
    ],
    "Resource": "*"
  }]
}
```
