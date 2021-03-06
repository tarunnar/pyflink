################################################################################
#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
################################################################################
import sys

from flink.plan.Environment import get_environment
from flink.functions.FlatMapFunction import FlatMapFunction
from flink.functions.FilterFunction import FilterFunction
from flink.functions.GroupReduceFunction import GroupReduceFunction
from flink.io.PythonInputFormat import PythonInputFormat, FileInputSplit
from flink.io.PythonOutputFormat import PythonOutputFormat


class Tokenizer(FlatMapFunction):
    def flat_map(self, value, collector):
        for word in value.lower().split():
            collector.collect((1, word))


class Adder(GroupReduceFunction):
    def reduce(self, iterator, collector):
        count, word = iterator.next()
        count += sum([x[0] for x in iterator])
        collector.collect((count, word))


class MyFormat(PythonInputFormat):
    def __init__(self):
        super(MyFormat, self).__init__()

    def createInputSplits(self, minNumSplits, path, collector):
        collector.collect(FileInputSplit(path, 0, 1, ("localhost",)))

    def deliver(self, path, collector):
        collector.collect("hello")
        collector.collect("world")

class Filter(FilterFunction):
    def __init__(self):
        super(Filter, self).__init__()

    def filter(self, value):
        print(0)
        return False

class GMSOF(PythonOutputFormat):
    def write(self, value):
        print("value", type(value))
        sys.stdout.flush()



if __name__ == "__main__":
    env = get_environment()
    if len(sys.argv) != 1 and len(sys.argv) != 3:
        sys.exit("Usage: ./bin/pyflink.sh WordCount[ - <text path> <result path>]")

    if len(sys.argv) == 3:
        data = env.read_text(sys.argv[1])
    else:
        data = env.read_custom("/opt/gms_sample/227064_000202_BLA_SR.hdr", "*", True, MyFormat())
        #data = env.from_elements("hello","world","hello","car","tree","data","hello")

    result = data \
        .flat_map(Tokenizer()) \
        .group_by(1) \
        .reduce_group(Adder(), combinable=True) \

    if len(sys.argv) == 3:
        result.write_csv(sys.argv[2])
    else:
        result.write_custom(GMSOF("/opt/output"))
        filtered = result.filter(Filter())
        filtered.write_custom(GMSOF("/opt/output"))
        #result.output()

    env.set_parallelism(1)

    env.execute(local=True)
