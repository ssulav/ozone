/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdds.utils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

import org.apache.hadoop.hdds.server.OzoneAdmins;
import org.apache.hadoop.hdds.utils.db.DBCheckpoint;
import org.apache.hadoop.hdds.utils.db.DBStore;

import org.apache.commons.lang3.StringUtils;

import static org.apache.hadoop.hdds.utils.HddsServerUtil.writeDBCheckpointToStream;
import static org.apache.hadoop.ozone.OzoneConsts.OZONE_DB_CHECKPOINT_REQUEST_FLUSH;

import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the current checkpoint Snapshot of the OM/SCM DB. (tar)
 */
public class DBCheckpointServlet extends HttpServlet {

  private static final Logger LOG =
      LoggerFactory.getLogger(DBCheckpointServlet.class);
  private static final long serialVersionUID = 1L;

  private transient DBStore dbStore;
  private transient DBCheckpointMetrics dbMetrics;

  private boolean aclEnabled;
  private boolean isSpnegoEnabled;
  private transient OzoneAdmins admins;

  public void initialize(DBStore store, DBCheckpointMetrics metrics,
                         boolean omAclEnabled,
                         Collection<String> allowedAdminUsers,
                         Collection<String> allowedAdminGroups,
                         boolean isSpnegoAuthEnabled)
      throws ServletException {

    dbStore = store;
    dbMetrics = metrics;
    if (dbStore == null) {
      LOG.error(
          "Unable to set metadata snapshot request. DB Store is null");
    }

    this.aclEnabled = omAclEnabled;
    this.admins = new OzoneAdmins(allowedAdminUsers, allowedAdminGroups);
    this.isSpnegoEnabled = isSpnegoAuthEnabled;
  }

  private boolean hasPermission(UserGroupInformation user) {
    // Check ACL for dbCheckpoint only when global Ozone ACL and SPNEGO is
    // enabled
    if (aclEnabled && isSpnegoEnabled) {
      return admins.isAdmin(user);
    } else {
      return true;
    }
  }

  /**
   * Process a GET request for the DB checkpoint snapshot.
   *
   * @param request  The servlet request we are processing
   * @param response The servlet response we are creating
   */
  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response) {

    LOG.info("Received request to obtain DB checkpoint snapshot");
    if (dbStore == null) {
      LOG.error(
          "Unable to process metadata snapshot request. DB Store is null");
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      return;
    }

    // Check ACL for dbCheckpoint only when global Ozone ACL is enabled
    if (aclEnabled) {
      final java.security.Principal userPrincipal = request.getUserPrincipal();
      if (userPrincipal == null) {
        final String remoteUser = request.getRemoteUser();
        LOG.error("Permission denied: Unauthorized access to /dbCheckpoint,"
                + " no user principal found. Current login user is {}.",
            remoteUser != null ? "'" + remoteUser + "'" : "UNKNOWN");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return;
      } else {
        final String userPrincipalName = userPrincipal.getName();
        UserGroupInformation ugi =
            UserGroupInformation.createRemoteUser(userPrincipalName);
        if (!hasPermission(ugi)) {
          LOG.error("Permission denied: User principal '{}' does not have"
                  + " access to /dbCheckpoint.\nThis can happen when Ozone"
                  + " Manager is started with a different user.\n"
                  + " Please append '{}' to OM 'ozone.administrators'"
                  + " config and restart OM to grant current"
                  + " user access to this endpoint.",
              userPrincipalName, userPrincipalName);
          response.setStatus(HttpServletResponse.SC_FORBIDDEN);
          return;
        }
        LOG.debug("Granted user principal '{}' access to /dbCheckpoint.",
            userPrincipalName);
      }
    }

    DBCheckpoint checkpoint = null;
    try {

      boolean flush = false;
      String flushParam =
          request.getParameter(OZONE_DB_CHECKPOINT_REQUEST_FLUSH);
      if (StringUtils.isNotEmpty(flushParam)) {
        flush = Boolean.valueOf(flushParam);
      }

      checkpoint = dbStore.getCheckpoint(flush);
      if (checkpoint == null || checkpoint.getCheckpointLocation() == null) {
        LOG.error("Unable to process metadata snapshot request. " +
            "Checkpoint request returned null.");
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        return;
      }
      dbMetrics.setLastCheckpointCreationTimeTaken(
          checkpoint.checkpointCreationTimeTaken());

      Path file = checkpoint.getCheckpointLocation().getFileName();
      if (file == null) {
        return;
      }
      response.setContentType("application/x-tar");
      response.setHeader("Content-Disposition",
          "attachment; filename=\"" +
               file + ".tar\"");

      Instant start = Instant.now();
      writeDbDataToStream(checkpoint, request,
          response.getOutputStream());
      Instant end = Instant.now();

      long duration = Duration.between(start, end).toMillis();
      LOG.info("Time taken to write the checkpoint to response output " +
          "stream: {} milliseconds", duration);
      dbMetrics.setLastCheckpointStreamingTimeTaken(duration);
      dbMetrics.incNumCheckpoints();
    } catch (Exception e) {
      LOG.error(
          "Unable to process metadata snapshot request. ", e);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      dbMetrics.incNumCheckpointFails();
    } finally {
      if (checkpoint != null) {
        try {
          checkpoint.cleanupCheckpoint();
        } catch (IOException e) {
          LOG.error("Error trying to clean checkpoint at {} .",
              checkpoint.getCheckpointLocation().toString());
        }
      }
    }
  }

  /**
   * Write checkpoint to the stream.
   *
   * @param checkpoint The checkpoint to be written.
   * @param ignoredRequest The httpRequest which generated this checkpoint.
   *        (Parameter is ignored in this class but used in child classes).
   * @param destination The stream to write to.
   */
  public void writeDbDataToStream(DBCheckpoint checkpoint,
                                  HttpServletRequest ignoredRequest,
                                  OutputStream destination)
      throws IOException, InterruptedException {
    writeDBCheckpointToStream(checkpoint, destination);
  }

}
