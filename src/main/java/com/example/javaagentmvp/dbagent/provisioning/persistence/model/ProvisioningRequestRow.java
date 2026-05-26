package com.example.javaagentmvp.dbagent.provisioning.persistence.model;

public class ProvisioningRequestRow {

    private String id;
    private String title;
    private String host;
    private int sshPort;
    private String sshUser;
    private String authType;
    private String databaseName;
    private String schemaName;
    private int memoryMb;
    private int diskGb;
    private String dataDirectory;
    private String extensions;
    private String dbOwnerUser;
    private int pgMajorVersion;
    private String osFamily;
    private String osVersionLabel;
    private String status;
    private String errorSummary;
    private String connectionHint;
    private String createdAt;
    private String updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getSshPort() {
        return sshPort;
    }

    public void setSshPort(int sshPort) {
        this.sshPort = sshPort;
    }

    public String getSshUser() {
        return sshUser;
    }

    public void setSshUser(String sshUser) {
        this.sshUser = sshUser;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(int memoryMb) {
        this.memoryMb = memoryMb;
    }

    public int getDiskGb() {
        return diskGb;
    }

    public void setDiskGb(int diskGb) {
        this.diskGb = diskGb;
    }

    public String getDataDirectory() {
        return dataDirectory;
    }

    public void setDataDirectory(String dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public String getExtensions() {
        return extensions;
    }

    public void setExtensions(String extensions) {
        this.extensions = extensions;
    }

    public String getDbOwnerUser() {
        return dbOwnerUser;
    }

    public void setDbOwnerUser(String dbOwnerUser) {
        this.dbOwnerUser = dbOwnerUser;
    }

    public int getPgMajorVersion() {
        return pgMajorVersion;
    }

    public void setPgMajorVersion(int pgMajorVersion) {
        this.pgMajorVersion = pgMajorVersion;
    }

    public String getOsFamily() {
        return osFamily;
    }

    public void setOsFamily(String osFamily) {
        this.osFamily = osFamily;
    }

    public String getOsVersionLabel() {
        return osVersionLabel;
    }

    public void setOsVersionLabel(String osVersionLabel) {
        this.osVersionLabel = osVersionLabel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public String getConnectionHint() {
        return connectionHint;
    }

    public void setConnectionHint(String connectionHint) {
        this.connectionHint = connectionHint;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
}
