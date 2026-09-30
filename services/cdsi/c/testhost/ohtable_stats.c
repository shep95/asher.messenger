// Copyright 2022 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only

#include <inttypes.h>
#include <stdlib.h>
#include "util/util.h"

#include "testhost/ohtable_stats.h"

static uint64_t read_stat(struct org_signal_cdsi_shard_statistics_t pb_stats, const char* name) {
    
    for(int i = 0; i < pb_stats.values.length; ++i) {
        if(strcmp(name, pb_stats.values.items_p[i].name_p) == 0) {
            return pb_stats.values.items_p[i].value;
        }
    }
    return UINT64_MAX;
}

error_t decode_statistics(size_t pbsize, uint8_t* pb, ohtable_statistics* totals) {
    error_t err = err_SUCCESS;
    size_t num_fields = 2;
    size_t max_field_name_len = 32;
    size_t len_overhead = 2;
    size_t workspace_size = len_overhead + sizeof(ohtable_statistics) + num_fields*(max_field_name_len+8) + 128;
    uint8_t *workspace;
    CHECK(workspace = calloc(workspace_size, 1));

    struct org_signal_cdsi_table_statistics_t *pb_stats = org_signal_cdsi_table_statistics_new(workspace, workspace_size);
    if (pb_stats == NULL) {
        err = err_HOST__TABLE_STATISTICS__PB_NEW;
        goto finish;
    }

    int size = org_signal_cdsi_table_statistics_decode(pb_stats, pb, pbsize);
    if(size < 0) {
        TEST_LOG("failed to decode stats pb. pbsize: %zu returned: %d workspace_size: %zu", pbsize, size, workspace_size);
        err = err_HOST__TABLE_STATISTICS__PB_DECODE;
        goto finish;
    }
    if(pb_stats->shard_statistics.length < 1) {
        err = err_HOST__TABLE_STATISTICS__PB_DECODE;
        goto finish;
    }

    struct org_signal_cdsi_shard_statistics_t table_stats = pb_stats->shard_statistics.items_p[0];
    fprintf(stderr, "TABLE: ");
    for(int i = 0; i < table_stats.values.length; ++i) {
        fprintf(stderr, "%s: %" PRIu64 " ", table_stats.values.items_p[i].name_p, table_stats.values.items_p[i].value);
    }
    fprintf(stderr, "\n");

    totals->num_items = read_stat(table_stats, "num_items");
    totals->capacity = read_stat(table_stats, "capacity");

finish:
    free(workspace);
    return err;
}
