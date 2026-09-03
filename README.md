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
  ./gradlew installToServer                 # the development box
  ./gradlew installToServer -Pbox=demotwo   # another box
  ```

**Which box.** The first of these that is set:

1. `-Pbox=<name>` on the command line.
2. `CANEXER_BOX` in the environment, for a build run outside the composite.
3. `canexer.box`, a system property the `canexer-workspace` composite sets from
   the name on this machine's data volume, which adoption wrote: the development
   box.
4. No box, but `CANEXER_URL` in the environment: the install goes to that
   server.
5. Nothing at all: the install goes to `http://localhost:8088`.

**Where its server is.** A named box (1 to 3) is reached at the `url`
in its descriptor `~/.nimbox/boxes/<name>/box.json`, and a descriptor
without one is refused, never defaulted. `NIMBOX_BOXES` moves the
descriptors directory. Only 4 and 5 install without a box name, and so
cannot obtain a tower token; they are for an open manager plane.

**How the upload gets in.** The first of these that applies:

1. `CANEXER_SERVER_SECRET` in the environment is presented as the
   bearer, for CI.
2. The manager plane answers an unauthenticated probe: a DEVELOPMENT
   or INTEGRATION server. The upload goes as is.
3. The plane refuses: a box that takes tower tokens only. The plugin
   asks the tower for a five-minute token scoped to the box, with the
   session `tower login` stores in `~/.nimbox/tower.json`
   (`NIMBOX_TOWER_URL` points at another tower), and presents it on
   the upload and the job polls.
