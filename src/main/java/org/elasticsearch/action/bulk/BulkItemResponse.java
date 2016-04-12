/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.bulk;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.xcontent.StatusToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;

/**
 * Represents a single item response for an action executed as part of the bulk API. Holds the index/type/id
 * of the relevant action, and if it has failed or not (with the failure message incase it failed).
 */
public class BulkItemResponse implements Streamable, StatusToXContent {

    @Override
    public RestStatus status() {
        return failure == null ? response.status() : failure.getStatus();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(opType);
        if (failure == null) {
            response.toXContent(builder, params);
            builder.field(Fields.STATUS, response.status());
        } else {
            builder.field(Fields._INDEX, failure.getIndex());
            builder.field(Fields._TYPE, failure.getType());
            builder.field(Fields._ID, failure.getId());
            builder.field(Fields.STATUS, failure.getStatus());
            builder.startObject(Fields.ERROR);
            ElasticsearchException.toXContent(builder, params, failure.getCause());
            builder.endObject();
        }
        builder.endObject();
        return builder;
    }

    static final class Fields {
        static final XContentBuilderString _INDEX = new XContentBuilderString("_index");
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString _ID = new XContentBuilderString("_id");
        static final XContentBuilderString STATUS = new XContentBuilderString("status");
        static final XContentBuilderString ERROR = new XContentBuilderString("error");
    }

    /**
     * Represents a failure.
     */
    public static class Failure {
        private final String index;
        private final String type;
        private final String id;
        private final Throwable cause;
        private final RestStatus status;

        public Failure(String index, String type, String id, Throwable t) {
            this.index = index;
            this.type = type;
            this.id = id;
            this.cause = t;
            this.status = ExceptionsHelper.status(t);
        }

        /**
         * The index name of the action.
         */
        public String getIndex() {
            return this.index;
        }

        /**
         * The type of the action.
         */
        public String getType() {
            return type;
        }

        /**
         * The id of the action.
         */
        public String getId() {
            return id;
        }

        /**
         * The failure message.
         */
        public String getMessage() {
            return this.cause.toString();
        }

        /**
         * The rest status.
         */
        public RestStatus getStatus() {
            return this.status;
        }

        public Throwable getCause() {
            return cause;
        }
    }

    private int id;

    private String opType;

    private DocWriteResponse response;

    private Failure failure;

    BulkItemResponse() {

    }

    public BulkItemResponse(int id, String opType, DocWriteResponse response) {
        this.id = id;
        this.opType = opType;
        this.response = response;
    }

    public BulkItemResponse(int id, String opType, Failure failure) {
        this.id = id;
        this.opType = opType;
        this.failure = failure;
    }

    /**
     * The numeric order of the item matching the same request order in the bulk request.
     */
    public int getItemId() {
        return id;
    }

    /**
     * The operation type ("index", "create" or "delete").
     */
    public String getOpType() {
        return this.opType;
    }

    /**
     * The index name of the action.
     */
    public String getIndex() {
        if (failure != null) {
            return failure.getIndex();
        }
        return response.getIndex();
    }

    /**
     * The type of the action.
     */
    public String getType() {
        if (failure != null) {
            return failure.getType();
        }
        return response.getType();
    }

    /**
     * The id of the action.
     */
    public String getId() {
        if (failure != null) {
            return failure.getId();
        }
        return response.getId();
    }

    /**
     * The version of the action.
     */
    public long getVersion() {
        if (failure != null) {
            return -1;
        }
        return response.getVersion();
    }

    /**
     * The actual response ({@link IndexResponse} or {@link DeleteResponse}). <tt>null</tt> in
     * case of failure.
     */
    public <T extends DocWriteResponse> T getResponse() {
        return (T) response;
    }

    /**
     * Is this a failed execution of an operation.
     */
    public boolean isFailed() {
        return failure != null;
    }

    /**
     * The failure message, <tt>null</tt> if it did not fail.
     */
    public String getFailureMessage() {
        if (failure != null) {
            return failure.getMessage();
        }
        return null;
    }

    /**
     * The actual failure object if there was a failure.
     */
    public Failure getFailure() {
        return this.failure;
    }

    public static BulkItemResponse readBulkItem(StreamInput in) throws IOException {
        BulkItemResponse response = new BulkItemResponse();
        response.readFrom(in);
        return response;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        id = in.readVInt();
        opType = in.readString();

        byte type = in.readByte();
        if (type == 0) {
            response = new IndexResponse();
            response.readFrom(in);
        } else if (type == 1) {
            response = new DeleteResponse();
            response.readFrom(in);
        } else if (type == 3) { // make 3 instead of 2, because 2 is already in use for 'no responses'
            response = new UpdateResponse();
            response.readFrom(in);
        }

        if (in.readBoolean()) {
            String fIndex = in.readString();
            String fType = in.readString();
            String fId = in.readOptionalString();
            Throwable throwable = in.readThrowable();
            failure = new Failure(fIndex, fType, fId, throwable);
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(id);
        out.writeString(opType);

        if (response == null) {
            out.writeByte((byte) 2);
        } else {
            if (response instanceof IndexResponse) {
                out.writeByte((byte) 0);
            } else if (response instanceof DeleteResponse) {
                out.writeByte((byte) 1);
            } else if (response instanceof UpdateResponse) {
                out.writeByte((byte) 3); // make 3 instead of 2, because 2 is already in use for 'no responses'
            }
            response.writeTo(out);
        }
        if (failure == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeString(failure.getIndex());
            out.writeString(failure.getType());
            out.writeOptionalString(failure.getId());
            out.writeThrowable(failure.getCause());
        }
    }
}
