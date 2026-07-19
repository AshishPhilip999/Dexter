package ContainerCore.Container;

import java.io.Serializable;

import ContainerCore.Utility.ContainerLimitType;
import ContainerCore.Utility.ContainerObject;
import StorageCore.Storage;
import StorageStatus.SPStatus;
import StorageStatus.Container.ContainerStatus;
import StorageStatus.Container.ContainerFailStatus.ContainerReadFail;
import StorageStatus.Container.ContainerFailStatus.ContainerWriteFail;
import StorageStatus.Container.ContainerSuccessStatus.ContainerReadSuccess;
import StorageStatus.Container.ContainerSuccessStatus.ContainerWriteSuccess;
import StorageStatus.Writer.IOStatus;
import StorageStatus.Writer.FailStatus.IOReadFail;
import StorageStatus.Writer.FailStatus.IOWriteFail;
import StorageStatus.Writer.SuccessStatus.IOReadSuccess;
import Utility.IO.DATIO;
import Utility.IO.FileService;
import Utility.IO.FileType;
import Utility.Serialization.Serializer;

public class ContainerValidator<T extends Serializable> {
    protected byte[] currentData;

    @SuppressWarnings("rawtypes")
    protected FileService fileService;

    protected final Class<T> valueObjectInstance;

    public byte[] getCurrentData() {
        return this.currentData;
    }

    public ContainerValidator(String containerName, Class<T> valueObjectInstance, Storage containerStorage,
            String fullPath, FileType fileType, boolean skipFileConfiguration) {
        this.valueObjectInstance = valueObjectInstance;
        if (!skipFileConfiguration) {
            this.configureFileWriter(containerName, containerStorage, fullPath, fileType);
        }
    }

    public synchronized ContainerObject<T> write(T data, long fileSize, ContainerLimitType containerLimitType,
            long containerLimit,
            long containerSize, int containerLength) {
        ContainerObject<T> containerObject = new ContainerObject<>();
        containerObject.status = this.writeInContainer(data, fileSize, containerLimitType, containerLimit,
                containerSize,
                containerLength);
        containerObject.object = data;

        return containerObject;
    }

    public ContainerObject<T> read() {
        return this.readInContainer();
    }

    protected ContainerObject<T> readInContainer() {
        ContainerObject<T> contObj = new ContainerObject<>();
        IOStatus readStatus = this.readInFile();
        contObj.status = this.handleFileReadResponse(readStatus, contObj);

        return contObj;
    }

    protected IOStatus readInFile() {
        return fileService.read();
    }

    protected ContainerStatus handleFileReadResponse(IOStatus readStatus, ContainerObject<T> contObj) {
        if (readStatus.getSPStatus() == SPStatus.SUCCESS) {
            IOReadSuccess success = (IOReadSuccess) readStatus;
            T object = this.getReadObject(success);

            if (object != null) {
                contObj.object = object;
                return ContainerReadSuccess.FINISH_READ;
            }
            return ContainerReadFail.SERIALIZE_ERROR;
        }

        IOReadFail readFailStatus = (IOReadFail) readStatus;
        return this.handleReadFailStatus(readFailStatus);
    }

    private ContainerReadFail handleReadFailStatus(IOReadFail readFailStatus) {
        if (readFailStatus == IOReadFail.KEY_NOT_FOUND) {
            return ContainerReadFail.KEY_NOT_FOUND;
        } else if (readFailStatus == IOReadFail.FILE_NOT_FOUND) {
            return ContainerReadFail.FILE_NOT_FOUND;
        }

        return ContainerReadFail.READ_ERROR;
    }

    private T getReadObject(IOReadSuccess status) {
        try {
            T obj = Serializer.deserialize(status.getData());
            if (this.valueObjectInstance.isInstance(obj)) {
                return obj;
            }
        } catch (Exception e) {
            // Log Exception
        }
        return null;
    }

    public boolean isContainerFull(long fileSize, ContainerLimitType containerLimitType, long containerLimit,
            long containerSize, int containerLength) {
        long value = containerLimitType.getValue();
        switch (containerLimitType) {
            case NO_LIMIT:
                return false;
            case LENGTH:
                return ((containerLimit + 1) > containerLength);

            default:
                return (containerLimit > (containerSize + fileSize / value));
        }
    }

    public SPStatus close() {
        try {
            fileService.close();
            return SPStatus.SUCCESS;
        } catch (Exception e) {
            // Log Exception
            return SPStatus.FAIL;
        }
    }

    protected ContainerStatus writeInContainer(T data, long fileSize, ContainerLimitType containerLimitType,
            long containerLimit,
            long containerSize, int containerLength) {
        SPStatus serializeStatus = this.serialize(data);
        ContainerStatus containerStatus;

        if (serializeStatus == SPStatus.SUCCESS) {
            try {
                if (!isContainerFull(fileSize, containerLimitType, containerLimit, containerSize, containerLength)) {
                    IOStatus writeResponse;
                    writeResponse = this.writeInFile(this.currentData);

                    containerStatus = this.handleWriteResponse(writeResponse, this.currentData);
                } else {
                    containerStatus = ContainerWriteFail.LIMIT_EXCEEDED;
                }
            } catch (Exception e) {
                // Log Exception
                containerStatus = ContainerWriteFail.WRITE_ERROR;
            }
        } else {
            containerStatus = ContainerWriteFail.CANNOT_SERIALIZE;
        }

        return containerStatus;
    }

    protected IOStatus writeInFile(byte[] currentData) {
        return this.fileService.write(this.currentData);
    }

    protected ContainerStatus handleWriteResponse(IOStatus writeResponse, byte[] writeData) {
        if (writeResponse.getSPStatus() == SPStatus.SUCCESS) {
            ContainerWriteSuccess success = ContainerWriteSuccess.FINISH_WRITE;
            success.setByteData(writeData);
            return ContainerWriteSuccess.FINISH_WRITE;
        }

        IOWriteFail failStatus = (IOWriteFail) writeResponse;
        return this.handleFailedWriteStatus(failStatus);
    }

    protected ContainerWriteFail handleFailedWriteStatus(IOWriteFail failstatus) {
        switch (failstatus) {
            case FILE_NOT_FOUND:
                return ContainerWriteFail.FILE_NOT_FOUND;

            case DUPLICATE_KEY_FOUND:
                return ContainerWriteFail.DUPLICATE_KEY_FOUND;

            case KEY_NOT_FOUND:
                return ContainerWriteFail.KEY_NOT_FOUND;

            case NULL_DATA_PROVIDED:
                return ContainerWriteFail.NULL_DATA_PROVIDED;

            case CANNOT_WRITE:
                return ContainerWriteFail.WRITE_ERROR;
        }

        return ContainerWriteFail.FILE_NOT_FOUND;
    }

    protected SPStatus serialize(Object data) {
        try {
            this.currentData = Serializer.serialize(data);
            return SPStatus.SUCCESS;
        } catch (Exception e) {
            // Log Exception
        }
        return SPStatus.FAIL;
    }

    @SuppressWarnings("rawtypes")
    protected void configureFileWriter(String containerName, Storage containerStorage, String fullPath,
            FileType fileType) {
        String filePath;
        if (containerStorage != null) {
            filePath = fullPath;
        } else {
            filePath = containerName;
        }

        switch (fileType) {
            case BINARY:
                // fileWriter = new BinaryWriter(filePath);
                break;
            case DAT:
                try {
                    this.fileService = new DATIO(filePath);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case JSON:
                // fileWriter = new JSONWriter(filePath);
                break;
            case ANY:
                // fileWriter = new AnyWriter(filePath);
                break;
        }
    }
}
