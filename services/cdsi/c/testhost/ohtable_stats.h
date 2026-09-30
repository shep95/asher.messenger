// Copyright 2022 Signal Messenger, LLC
// SPDX-License-Identifier: AGPL-3.0-only

#ifndef __CDSI_TESTHOST_OHTABLE_STATS_H
#define __CDSI_TESTHOST_OHTABLE_STATS_H

#include "proto/cdsi.h"
#include "util/error.h"
#include "util/statistics.h"

/**
 * @brief decode protobuf enclave statistics
 *
 * The enclave reports table-wide totals, not per-shard values. Only the
 * `num_items` and `capacity` members of `totals` are written.
 *
 * @param pbsize
 * @param pb
 * @param totals `ohtable_statistics` struct. Results will be written here.
 */
error_t decode_statistics(size_t pbsize, uint8_t* pb, ohtable_statistics* totals);

#endif // __CDSI_TESTHOST_OHTABLE_STATS_H
