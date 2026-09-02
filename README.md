# development

## workflows

In the `.github/workflows` directory, you can find the following workflows:

* **`java-release.yaml`**: Used to release a new version of a java
  library to the github java repository.


## gradle-plugins

Plugins every canexer repository applies, published to GitHub Packages and
versioned with this repository's tag.

* **`com.nimbox.tools.versioning`**: derives the project version from the
  git tag.
* **`com.nimbox.canexer.artifact`**: stamps the `Nimbox-*` manifest on an
  application war or a connector jar and registers `installToServer`, which
  uploads the archive to a box's control plane
  (`POST /server/manager/install`) and waits for the install job to settle.

  ```
  ./gradlew installToServer                 # the local server, http://localhost:8088
  ./gradlew installToServer -Pbox=demotwo   # the box named in ~/.nimbox/boxes/demotwo/box.json
  ```

  `-Pbox` (or `CANEXER_BOX`) names the box; its descriptor's `url` says where
  the server is (`NIMBOX_BOXES` moves the descriptors). Without a box,
  `CANEXER_URL` or localhost. A DEVELOPMENT or INTEGRATION server takes the
  upload as is; a box that takes tower tokens only gets one from the tower,
  five minutes and scoped to the box, using the session `tower login`
  stores in `~/.nimbox/tower.json` (`NIMBOX_TOWER_URL` points at another
  tower). `CANEXER_SERVER_SECRET`, when set, is presented as the bearer
  instead, for CI.
