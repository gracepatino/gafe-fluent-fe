# Description

This repository is a sample of how to run Apryse's Fluent application in a
docker compose file.

## Prerequisites

You will need docker and yarn installed on your system. This document assumes
that you have [Homebrew](https://brew.sh/) if you're running on Mac and
[Winget](https://learn.microsoft.com/en-us/windows/package-manager/winget/) on
Windows.

```sh
# Mac
brew install --cask docker

# Windows
winget install Docker.DockerDesktop
```

> Running docker on windows requires you to have the
> [Windows WSL](https://docs.docker.com/desktop/setup/install/windows-install/)
> layer installed.

Once docker is installed, simply run docker desktop. This will setup the docker
daemon. To validate that you have installed it correctly, simply run

```sh
docker --version
docker compose version
```

If you get an output, then you have successfully installed docker correctly.

## Usage

```sh
yarn install
docker compose up

# Navigate to localhost in your web browser and use credentials
# Username: admin@fake.email
# Password: admin-pa73305word
```
