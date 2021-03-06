(ns cortex.compute.cuda.tensor-math
  (:require [cortex.compute.cuda.driver :refer [value->ptr ->ptr] :as cuda-base]
            [think.datatype.core :as dtype]
            [cortex.tensor.math :as tm]
            [cortex.compute.driver :as drv]
            [cortex.compute.cpu.tensor-math :as cpu-tens-math]
            [cortex.compute.math-util :as cmu]
            [think.resource.core :as resource])
  (:import [cortex.compute.cuda.driver CudaStream]
           [org.bytedeco.javacpp Pointer IntPointer DoublePointer FloatPointer SizeTPointer
            cublas cublas$cublasContext
            cudnn cudnn$cudnnContext
            cudnn$cudnnConvolutionStruct
            cudnn$cudnnFilterStruct]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn- dimensions->cuda
  [{:keys [shape strides]}]
  (let [max-shape-count 5
        rev-shape (->> (concat (reverse shape)
                               (repeat 1))
                       (take max-shape-count)
                       vec)
        rev-strides (->> (concat (reverse strides)
                                 (repeat (first strides)))
                         (take max-shape-count)
                         vec)]
    (->> (concat rev-shape rev-strides)
         (mapv int))))


(defn- operation->cuda
  ([operation]
   [(int (condp = operation
           :+ 0
           :- 1
           :* 2
           :/ 3
           :min 4
           :max 5
           :bit-and 6))])
  ([operation rev-ops?]
   (conj (operation->cuda operation)
         (int (if rev-ops? 1 0)))))


(defn- unary-op->cuda
  ^Integer [operation]
  [(condp = operation
     :floor (int 0)
     :ceil (int 1)
     :round (int 2)
     :- (int 3)
     :tanh (int 4)
     :logistic (int 5))])


(defn- ternary-op->cuda
  ^Integer [operation]
  [(condp = operation
     :select (int 0))])



(defonce cuda_typename_expansion
  [["int8_t" "_b"]
   ["int16_t" "_s"]
   ["int32_t" "_i"]
   ["int64_t" "_l"]
   ["f32_t" "_f"]
   ["f64_t" "_d"]])


(defn- print-datatypes-h-2-dtype-expansion
  []
  (with-out-str
    (println "#define ITERATE_2_DATATYPES\\")
    (doall
     (for [lhs cuda_typename_expansion
           rhs cuda_typename_expansion]
       (println (apply format "  DATATYPE_2_ITERATOR(%s,%s,%s,%s)\\"
                       (flatten [lhs rhs])))))))


(defn- to-double-ptr
  ^DoublePointer [obj]
  (cuda-base/->ptr obj))


(defn- to-float-ptr
  ^FloatPointer [obj]
  (cuda-base/->ptr obj))



(defmacro ^:private blas-macro-iter
  [inner-macro]
  `{:double (~inner-macro to-double-ptr double cuda-base/value->double-ptr cublas/cublasDgemm_v2 cublas/cublasDgemv_v2)
    :float (~inner-macro to-float-ptr float cuda-base/value->float-ptr cublas/cublasSgemm_v2 cublas/cublasSgemv_v2)})


(defmacro ^:private blas-impl
  [ptr-cast-fn scalar-cast-fn scalar-ptr-fn gemm-fn gemv-fn]
  `{:gemm (fn [stream# trans-a?# trans-b?# a-row-count# a-col-count# b-col-count#
               alpha# A# a-rowstride#
               B# b-rowstride#
               beta# C# c-rowstride#]
            (cuda-base/blas-with-stream
             stream#
             (cuda-base/cublas-call
              (~gemm-fn
               ^cublas$cublasContext ~'cublas
               (cuda-base/bool->blas-trans trans-a?#)
               (cuda-base/bool->blas-trans trans-b?#)
               (long a-row-count#) (long b-col-count#) (long a-col-count#)
               (~scalar-ptr-fn alpha#)
               (~ptr-cast-fn A#)
               (int a-rowstride#)
               (~ptr-cast-fn B#)
               (int b-rowstride#)
               (~scalar-ptr-fn beta#)
               (~ptr-cast-fn C#)
               (int c-rowstride#)))))
    :gemv (fn [stream# trans-a?# a-row-count# a-col-count#
               alpha# A# a-rowstride#
               x# inc-x#
               beta# y# inc-y#]
            (cuda-base/blas-with-stream
             stream#
             (cuda-base/cublas-call
              (~gemv-fn
               ^cublas$cublasContext ~'cublas
               (cuda-base/bool->blas-trans trans-a?#)
               (long a-row-count#) (long a-col-count#)
               (~scalar-ptr-fn alpha#)
               (~ptr-cast-fn A#)
               (int a-rowstride#)
               (~ptr-cast-fn x#)
               (long inc-x#)
               (~scalar-ptr-fn beta#)
               (~ptr-cast-fn y#)
               (long inc-y#)))))})


(def ^:private blas-fn-map
  (blas-macro-iter blas-impl))

(defn act-type->cudnn-activation
  [act-type]
  (condp = act-type
    :relu cudnn/CUDNN_ACTIVATION_RELU
    :logistic cudnn/CUDNN_ACTIVATION_SIGMOID
    :tanh cudnn/CUDNN_ACTIVATION_TANH))


(defn- act-type->cudnn
  [act-type]
  (cuda-base/activation-description (act-type->cudnn-activation act-type)))


(defn dimensions->tensor
  [dimensions dtype]
  (cuda-base/tensor-with-strides dtype (get dimensions :shape) (get dimensions :strides)))


(defn- cudnn-activation!
  [stream input input-dims input-alpha
   output output-dims act-type]
  (resource/with-resource-context
    (let [dest-dtype (dtype/get-datatype output)
          in-tensor (dimensions->tensor input-dims dest-dtype)
          out-tensor (if (= input-dims output-dims)
                       in-tensor
                       (dimensions->tensor output-dims dest-dtype))]
      (cuda-base/cudnn-with-stream
       stream
       (cuda-base/cudnn-call
        (cudnn/cudnnActivationForward cudnn-context (act-type->cudnn act-type)
                                      (value->ptr input-alpha dest-dtype) in-tensor (->ptr input)
                                      (value->ptr 0 dest-dtype) out-tensor (->ptr output)))))))


(defn arg-order->indexes
  [arg-order]
  (->> (cpu-tens-math/arg-order->indexes arg-order)
       (mapv #(byte %))))


(defrecord ConvDesc [^cudnn$cudnnConvolutionStruct conv-desc
                     ^cudnn$cudnnFilterStruct filter-desc]
  resource/PResource
  (release-resource [this]))


(extend-type CudaStream
  tm/TensorMath
  (assign-constant! [stream buffer dimensions value n-elems]
    (let [datatype (dtype/get-datatype buffer)
          value (drv/dtype-cast value datatype)
          assign-fn (cuda-base/get-or-create-fn stream :tensor-assign-constant datatype
                                                #(cuda-base/load-all-datatype-function
                                                  "tensor_assign_constant"))
          n-elems (long n-elems)]
      (apply cuda-base/launch-linear-kernel
             (concat [stream assign-fn n-elems 0
                      (cuda-base/->ptr buffer)]
                     (dimensions->cuda dimensions)
                     [value n-elems]))))
  (assign! [stream
            dest dest-dims
            src src-dims
            n-elems]
    (let [lhs-dtype (dtype/get-datatype dest)
          rhs-dtype (dtype/get-datatype src)
          assign-fn (cuda-base/get-or-create-fn stream :tensor-assign
                                                [lhs-dtype rhs-dtype]
                                                #(cuda-base/load-2-datatype-function
                                                  "tensor_assign"))]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream assign-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(cuda-base/->ptr src)]
                         (dimensions->cuda src-dims)
                         [n-elems])
                 vec))))

  (unary-accum! [stream
                 dest dest-dims
                 alpha op n-elems]
    (let [dest-dtype (dtype/get-datatype dest)
          unop-fn (cuda-base/get-or-create-fn stream :tensor-unary-accum
                                              dest-dtype
                                              #(cuda-base/load-cas-datatype-function
                                                "tensor_unary_accum"))]
      (if (and (or (= dest-dtype :float)
                   (= dest-dtype :double))
               (or (= op :logistic)
                   (= op :tanh)))
        (cudnn-activation! stream dest dest-dims alpha dest dest-dims op)
        (apply cuda-base/launch-linear-kernel
               (-> (concat [stream unop-fn n-elems 0]
                           [(cuda-base/->ptr dest)]
                           (dimensions->cuda dest-dims)
                           [(drv/dtype-cast alpha dest-dtype)]
                           (unary-op->cuda op)
                           [n-elems])
                   vec)))))

  (unary-op! [stream
              dest dest-dims
              x x-dims
              alpha op n-elems]
    (let [dest-dtype (dtype/get-datatype dest)
          unop-fn (cuda-base/get-or-create-fn stream :tensor-unary-op
                                              dest-dtype
                                              #(cuda-base/load-all-datatype-function
                                                "tensor_unary_op"))]
      (if (and (or (= dest-dtype :float)
                   (= dest-dtype :double))
               (or (= op :logistic)
                   (= op :tanh))
               ;;Make sure no broadcasting
               (= dest-dims x-dims))
        (cudnn-activation! stream x x-dims alpha dest dest-dims op)
        (apply cuda-base/launch-linear-kernel
               (-> (concat [stream unop-fn n-elems 0]
                           [(cuda-base/->ptr dest)]
                           (dimensions->cuda dest-dims)
                           [(cuda-base/->ptr x)]
                           (dimensions->cuda x-dims)
                           [(drv/dtype-cast alpha dest-dtype)]
                           (unary-op->cuda op)
                           [n-elems])
                   vec)))))

  (binary-accum-constant! [stream
                           dest dest-dims dest-alpha
                           scalar
                           n-elems operation reverse-operands?]
    (let [dest-dtype (dtype/get-datatype dest)
          binop-fn (cuda-base/get-or-create-fn stream :tensor-accum-constant
                                               dest-dtype
                                               #(cuda-base/load-cas-datatype-function
                                                 "tensor_accum_constant"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (if (and (= :max operation)
               (= 0.0 scalar)
               (or (= dest-dtype :double)
                   (= dest-dtype :float)))
        (cudnn-activation! stream dest dest-dims dest-alpha dest dest-dims :relu)
        (apply cuda-base/launch-linear-kernel
               (-> (concat [stream binop-fn n-elems 0]
                           [(cuda-base/->ptr dest)]
                           (dimensions->cuda dest-dims)
                           [(->dtype dest-alpha) (->dtype scalar)]
                           (operation->cuda operation reverse-operands?)
                           [n-elems])
                   vec)))))

  (binary-op-constant! [stream
                        dest dest-dims
                        x x-dims x-alpha
                        scalar
                        n-elems operation reverse-operands?]
    (let [dest-dtype (dtype/get-datatype dest)
          binop-fn (cuda-base/get-or-create-fn stream :tensor-binary-op-constant
                                               dest-dtype
                                               #(cuda-base/load-all-datatype-function
                                                 "tensor_binary_op_constant"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (if (and (= x-dims dest-dims)
               (= 0.0 (double scalar))
               (= :max operation)
               (or (= dest-dtype :double)
                   (= dest-dtype :float)))
        (cudnn-activation! stream x x-dims x-alpha dest dest-dims :relu)
        (apply cuda-base/launch-linear-kernel
               (-> (concat [stream binop-fn n-elems 0]
                           [(cuda-base/->ptr dest)]
                           (dimensions->cuda dest-dims)
                           [(cuda-base/->ptr x)]
                           (dimensions->cuda x-dims)
                           [(->dtype x-alpha) (->dtype scalar)]
                           (operation->cuda operation reverse-operands?)
                           [n-elems])
                   vec)))))

  (binary-accum! [stream
                  dest dest-dims dest-alpha
                  y y-dims y-alpha
                  n-elems operation reverse-operands?]
    (let [dest-dtype (dtype/get-datatype dest)
          binop-fn (cuda-base/get-or-create-fn stream :tensor-binary-accum
                                               dest-dtype
                                               #(cuda-base/load-cas-datatype-function
                                                 "tensor_binary_accum"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream binop-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(->dtype dest-alpha)]
                         [(cuda-base/->ptr y)]
                         (dimensions->cuda y-dims)
                         [(->dtype y-alpha)]
                         (operation->cuda operation reverse-operands?)
                         [n-elems])
                 vec))))

  (binary-op! [stream
               dest dest-dims
               x x-dims x-alpha
               y y-dims y-alpha
               n-elems operation]
    (let [dest-dtype (dtype/get-datatype dest)
          binop-fn (cuda-base/get-or-create-fn stream :tensor-binary-op
                                               dest-dtype
                                               #(cuda-base/load-all-datatype-function
                                                 "tensor_binary_op"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream binop-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(cuda-base/->ptr x)]
                         (dimensions->cuda x-dims)
                         [(->dtype x-alpha)]
                         [(cuda-base/->ptr y)]
                         (dimensions->cuda y-dims)
                         [(->dtype y-alpha)]
                         (operation->cuda operation)
                         [n-elems])
                 vec))))

  (ternary-op! [stream
                dest dest-dims
                x x-dims x-alpha
                y y-dims y-alpha
                z z-dims z-alpha
                n-elems
                operation]
    (let [dest-dtype (dtype/get-datatype dest)
          ternop-fn (cuda-base/get-or-create-fn stream :tensor-ternary-op
                                                dest-dtype
                                                #(cuda-base/load-all-datatype-function
                                                  "tensor_ternary_op"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream ternop-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(cuda-base/->ptr x)]
                         (dimensions->cuda x-dims)
                         [(->dtype x-alpha)]
                         [(cuda-base/->ptr y)]
                         (dimensions->cuda y-dims)
                         [(->dtype y-alpha)]
                         [(cuda-base/->ptr z)]
                         (dimensions->cuda z-dims)
                         [(->dtype z-alpha)]
                         (ternary-op->cuda operation)
                         [n-elems])
                 vec))))

  (ternary-op-constant! [stream
                         dest dest-dims
                         a a-dims a-alpha
                         b b-dims b-alpha
                         constant
                         n-elems
                         operation arg-order]
    (let [dest-dtype (dtype/get-datatype dest)
          ternop-fn (cuda-base/get-or-create-fn stream :tensor-ternary-op-constant
                                                dest-dtype
                                                #(cuda-base/load-all-datatype-function
                                                  "tensor_ternary_op_constant"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream ternop-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(cuda-base/->ptr a)]
                         (dimensions->cuda a-dims)
                         [(->dtype a-alpha)]
                         [(cuda-base/->ptr b)]
                         (dimensions->cuda b-dims)
                         [(->dtype b-alpha)]
                         [(->dtype constant)]
                         (ternary-op->cuda operation)
                         (arg-order->indexes arg-order)
                         [n-elems])
                 vec))))

  (ternary-op-constant-constant! [stream
                                  dest dest-dims
                                  a a-dims a-alpha
                                  const-1
                                  const-2
                                  n-elems
                                  operation arg-order]
    (let [dest-dtype (dtype/get-datatype dest)
          ternop-fn (cuda-base/get-or-create-fn stream :tensor-ternary-op-constant_constant
                                                dest-dtype
                                                #(cuda-base/load-all-datatype-function
                                                  "tensor_ternary_op_constant_constant"))
          ->dtype #(drv/dtype-cast % dest-dtype)]
      (apply cuda-base/launch-linear-kernel
             (-> (concat [stream ternop-fn n-elems 0]
                         [(cuda-base/->ptr dest)]
                         (dimensions->cuda dest-dims)
                         [(cuda-base/->ptr a)]
                         (dimensions->cuda a-dims)
                         [(->dtype a-alpha)]
                         [(->dtype const-1)]
                         [(->dtype const-2)]
                         (ternary-op->cuda operation)
                         (arg-order->indexes arg-order)
                         [n-elems])
                 vec))))

  (gemm! [stream
          C c-colstride
          trans-a? trans-b? alpha
          A a-row-count a-col-count a-colstride
          B b-col-count b-colstride
          beta]
    (comment
      (println "gemm-args" {:c c-colstride
                            :a [a-row-count a-col-count a-colstride]
                            :b [b-col-count b-colstride]
                            }))
    (cmu/col->row-gemm
     (partial (get-in blas-fn-map [(dtype/get-datatype C) :gemm]) stream)
     trans-a? trans-b? a-row-count a-col-count b-col-count
     alpha A a-colstride
     B b-colstride
     beta C c-colstride))

  (gemv! [stream
          c inc-c
          trans-a? alpha
          A a-row-count a-col-count a-colstride
          x inc-x
          beta]
    (cmu/col->row-gemv
     (partial (get-in blas-fn-map [(dtype/get-datatype c) :gemv]) stream)
     trans-a? a-row-count a-col-count alpha A a-colstride
     x inc-x beta c inc-c))

  (batch-normalize-eltwise! [stream
                             output input means variances scale bias epsilon
                             batch-count
                             element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count 1 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 1 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationForwardInference
           cudnn-context cudnn/CUDNN_BATCHNORM_PER_ACTIVATION
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output)
           var-tensor
           (->ptr scale)
           (->ptr bias)
           (->ptr means)
           (->ptr variances)
           (double epsilon)))))))

  (batch-normalize-spatial! [stream
                             output input means variances scale bias epsilon
                             batch-count channel-count element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count channel-count 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 channel-count 1 1)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationForwardInference
           cudnn-context cudnn/CUDNN_BATCHNORM_SPATIAL
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output)
           var-tensor
           (->ptr scale)
           (->ptr bias)
           (->ptr means)
           (->ptr variances)
           (double epsilon)))))))

  (batch-normalize-update-and-apply-eltwise! [stream
                                              output input
                                              batch-means batch-variances
                                              running-means running-variances
                                              average-factor
                                              scale bias epsilon
                                              batch-count element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count 1 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 1 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationForwardTraining
           cudnn-context cudnn/CUDNN_BATCHNORM_PER_ACTIVATION
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output)
           var-tensor
           (->ptr scale)
           (->ptr bias)
           (double average-factor)
           (->ptr running-means)
           (->ptr running-variances)
           (double epsilon)
           (->ptr batch-means)
           (->ptr batch-variances)))))))

  (batch-normalize-update-and-apply-spatial! [stream
                                              output input
                                              batch-means batch-variances
                                              running-means running-variances
                                              average-factor
                                              scale bias epsilon
                                              batch-count channel-count element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count channel-count 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 channel-count 1 1)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationForwardTraining
           cudnn-context cudnn/CUDNN_BATCHNORM_SPATIAL
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output)
           var-tensor
           (->ptr scale)
           (->ptr bias)
           (double average-factor)
           (->ptr running-means)
           (->ptr running-variances)
           (double epsilon)
           (->ptr batch-means)
           (->ptr batch-variances)))))))

  (batch-normalize-gradients-eltwise! [stream
                                       input-gradient scale-gradient
                                       bias-gradient output-gradient
                                       output input batch-means batch-variances
                                       scale bias epsilon
                                       batch-count element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count 1 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 1 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationBackward
           cudnn-context cudnn/CUDNN_BATCHNORM_PER_ACTIVATION
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output-gradient)
           io-tensor
           (->ptr input-gradient)
           var-tensor
           (->ptr scale)
           (->ptr scale-gradient)
           (->ptr bias-gradient)
           (double epsilon)
           (->ptr batch-means)
           (->ptr batch-variances)))))))

  (batch-normalize-gradients-spatial! [stream
                                       input-gradient scale-gradient
                                       bias-gradient output-gradient
                                       output input batch-means batch-variances
                                       scale bias epsilon
                                       batch-count channel-count element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input)
            io-tensor (cuda-base/tensor datatype batch-count channel-count 1 element-count)
            var-tensor (cuda-base/tensor datatype 1 channel-count 1 1)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnBatchNormalizationBackward
           cudnn-context cudnn/CUDNN_BATCHNORM_SPATIAL
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           (value->ptr 1.0 datatype) ;;alpha
           (value->ptr 0.0 datatype) ;;beta
           io-tensor
           (->ptr input)
           io-tensor
           (->ptr output-gradient)
           io-tensor
           (->ptr input-gradient)
           var-tensor
           (->ptr scale)
           (->ptr scale-gradient)
           (->ptr bias-gradient)
           (double epsilon)
           (->ptr batch-means)
           (->ptr batch-variances)))))))

  (activation-gradient! [stream
                         input-gradient
                         output-gradient
                         output
                         op
                         element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype input-gradient)
            tensor (cuda-base/tensor datatype 1 1 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnActivationBackward cudnn-context
                                         (act-type->cudnn op)
                                         (value->ptr 1 datatype)
                                         tensor
                                         (->ptr output)
                                         tensor
                                         (->ptr output-gradient)
                                         tensor
                                         (->ptr output)
                                         (value->ptr 0 datatype)
                                         tensor
                                         (->ptr input-gradient)))))))
  
  (softmax-eltwise! [stream
                     output
                     input
                     batch-count
                     element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype output)
            tensor (cuda-base/tensor datatype batch-count 1 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnSoftmaxForward cudnn-context
                                     cudnn/CUDNN_SOFTMAX_ACCURATE
                                     cudnn/CUDNN_SOFTMAX_MODE_INSTANCE
                                     (value->ptr 1 datatype)
                                     tensor
                                     (->ptr input)
                                     (value->ptr 0 datatype)
                                     tensor
                                     (->ptr output)))))))

  (softmax-spatial! [stream
                     output
                     input
                     batch-count
                     channel-count
                     element-count]
    (resource/with-resource-context
      (let [datatype (dtype/get-datatype output)
            tensor (cuda-base/tensor datatype batch-count channel-count 1 element-count)]
        (cuda-base/cudnn-with-stream
         stream
         (cuda-base/cudnn-call
          (cudnn/cudnnSoftmaxForward cudnn-context
                                     cudnn/CUDNN_SOFTMAX_ACCURATE
                                     cudnn/CUDNN_SOFTMAX_MODE_CHANNEL
                                     (value->ptr 1 datatype)
                                     tensor
                                     (->ptr input)
                                     (value->ptr 0 datatype)
                                     tensor
                                     (->ptr output)))))))

  (convolution-descriptor [stream
                           datatype out-channels in-channels kernel-width kernel-height
                           pad-x pad-y stride-x stride-y]
    (let [^cudnn$cudnnConvolutionStruct conv-desc (cudnn$cudnnConvolutionStruct.)
          ^cudnn$cudnnFilterStruct filter-desc (cudnn$cudnnFilterStruct. )
          tensor-datatype (cuda-base/dtype->cudnn datatype)]
      (cuda-base/cudnn-call (cudnn/cudnnCreateConvolutionDescriptor conv-desc))
      (cuda-base/cudnn-call (cudnn/cudnnCreateFilterDescriptor filter-desc))
      (resource/track conv-desc)
      (resource/track filter-desc)
      (cuda-base/cudnn-call (cudnn/cudnnSetFilter4dDescriptor filter-desc
                                                              tensor-datatype
                                                              cudnn/CUDNN_TENSOR_NCHW
                                                              out-channels in-channels
                                                              kernel-width kernel-height))
      (cuda-base/cudnn-call (cudnn/cudnnSetConvolution2dDescriptor conv-desc
                                                                   pad-y pad-x
                                                                   stride-y stride-x
                                                                   1 1
                                                                   cudnn/CUDNN_CROSS_CORRELATION))

      (->ConvDesc conv-desc filter-desc)))

  (choose-convolution-algorithms [stream conv-descriptor
                                  input-width input-height
                                  output-width output-height
                                  batch-size
                                  max-ideal-workspace-size use-defaults?]
    ;;game on
    (resource/with-resource-context
      (let [{:keys [datatype out-channels in-channels kernel-width kernel-height
                    pad-x pad-y stride-x stride-y descriptor]} conv-descriptor
            input-tensor (cuda-base/tensor datatype batch-size in-channels input-width input-height)
            output-tensor (cuda-base/tensor datatype batch-size out-channels output-width output-height)
            ^cudnn$cudnnConvolutionStruct conv-desc (:conv-desc descriptor)
            ^cudnn$cudnnFilterStruct filter-desc (:filter-desc descriptor)
            forward-algo (IntPointer. 1)
            forward-workspace-size (SizeTPointer. 1)
            backward-filter-algo (IntPointer. 1)
            backward-filter-workspace-size (SizeTPointer. 1)
            backward-data-algo (IntPointer. 1)
            backward-data-workspace-size (SizeTPointer. 1)
            output-size-check (int-array 4)
            max-ideal-workspace-size (long max-ideal-workspace-size)]

        (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionNdForwardOutputDim conv-desc
                                                                           input-tensor
                                                                           filter-desc
                                                                           4
                                                                           output-size-check))

        ;;If these don't match we get memory overwrite or over-read errors
        (let [[n c h w] (vec output-size-check)]
          (when-not (and (= h output-height)
                         (= w output-width))
            (throw (Exception. (format "Calculated output dimensions %s and cudnn output dimensions %s are off"
                                       [h w] [output-height output-width])))))
        (cuda-base/cudnn-with-stream
         stream

         (if-not use-defaults?
           (do
             (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionForwardAlgorithm
                                    cudnn-context
                                    input-tensor
                                    filter-desc
                                    conv-desc
                                    output-tensor
                                    cudnn/CUDNN_CONVOLUTION_FWD_SPECIFY_WORKSPACE_LIMIT
                                    max-ideal-workspace-size
                                    forward-algo))
             (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionBackwardFilterAlgorithm
                                    cudnn-context
                                    input-tensor
                                    output-tensor
                                    conv-desc
                                    filter-desc
                                    cudnn/CUDNN_CONVOLUTION_BWD_FILTER_SPECIFY_WORKSPACE_LIMIT
                                    max-ideal-workspace-size
                                    backward-filter-algo))
             (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionBackwardDataAlgorithm
                                    cudnn-context
                                    filter-desc
                                    output-tensor
                                    conv-desc
                                    input-tensor
                                    cudnn/CUDNN_CONVOLUTION_BWD_DATA_SPECIFY_WORKSPACE_LIMIT
                                    max-ideal-workspace-size
                                    backward-data-algo)))
           (do
             (dtype/set-value! forward-algo 0 cudnn/CUDNN_CONVOLUTION_FWD_ALGO_IMPLICIT_GEMM)
             (dtype/set-value! backward-filter-algo 0 cudnn/CUDNN_CONVOLUTION_BWD_FILTER_ALGO_0)
             (dtype/set-value! backward-data-algo 0 cudnn/CUDNN_CONVOLUTION_BWD_DATA_ALGO_0)))


         (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionForwardWorkspaceSize
                                cudnn-context
                                input-tensor
                                filter-desc
                                conv-desc
                                output-tensor
                                (.get forward-algo)
                                forward-workspace-size))

         (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionBackwardFilterWorkspaceSize
                                cudnn-context
                                input-tensor
                                output-tensor
                                conv-desc
                                filter-desc
                                (.get backward-filter-algo)
                                backward-filter-workspace-size))

         (cuda-base/cudnn-call (cudnn/cudnnGetConvolutionBackwardDataWorkspaceSize
                                cudnn-context
                                filter-desc
                                output-tensor
                                conv-desc
                                input-tensor
                                (.get backward-data-algo)
                                backward-data-workspace-size)))
        {:forward {:algorithm (dtype/get-value forward-algo 0)
                   :workspace-size (.get forward-workspace-size)}
         :backward-filter {:algorithm (dtype/get-value backward-filter-algo 0)
                           :workspace-size (.get backward-filter-workspace-size)}
         :backward-data {:algorithm (dtype/get-value backward-data-algo 0)
                         :workspace-size (.get backward-data-workspace-size 0)}
         :workspace-size (long (max (long (.get forward-workspace-size))
                                    (long (.get backward-filter-workspace-size))
                                    (long (.get backward-data-workspace-size))))})))

  (convolution-forward! [stream
                         output output-dims output-alpha
                         input input-dims
                         weights weight-dims
                         workspace workspace-ecount
                         conv-descriptor algorithms]
    (resource/with-resource-context
      (cuda-base/cudnn-with-stream
       stream
       (let [[batch-size in-channels in-height in-width] (get input-dims :shape)
             [batch-size out-channels out-height out-width] (get output-dims :shape)
             {:keys [datatype descriptor]} conv-descriptor
             input-tensor (cuda-base/tensor datatype batch-size in-channels in-width in-height)
             output-tensor (cuda-base/tensor datatype batch-size out-channels out-width out-height)
             ^cudnn$cudnnConvolutionStruct conv-desc (:conv-desc descriptor)
             ^cudnn$cudnnFilterStruct filter-desc (:filter-desc descriptor)
             forward-algorithm (long (get-in algorithms [:forward :algorithm]))]
         (cuda-base/cudnn-call
          (cudnn/cudnnConvolutionForward
           cudnn-context
           (value->ptr 1 datatype)
           input-tensor
           (->ptr input)
           filter-desc
           (->ptr weights)
           conv-desc
           forward-algorithm
           (->ptr workspace)
           (int workspace-ecount)
           (value->ptr output-alpha datatype)
           output-tensor
           (->ptr output)))))))

  (convolution-backward-weights! [stream
                                  weight-gradient weight-gradient-dims weight-gradient-alpha
                                  output-gradient output-gradient-dims
                                  input input-dims
                                  workspace workspace-ecount
                                  conv-descriptor algorithms]
    (resource/with-resource-context
      (cuda-base/cudnn-with-stream
       stream
       (let [{:keys [datatype descriptor]} conv-descriptor
             [batch-size in-channels in-height in-width] (get input-dims :shape)
             [batch-size out-channels out-height out-width] (get output-gradient-dims :shape)
             input-tensor (cuda-base/tensor datatype batch-size in-channels in-width in-height)
             output-tensor (cuda-base/tensor datatype batch-size out-channels out-width out-height)
             ^cudnn$cudnnConvolutionStruct conv-desc (:conv-desc descriptor)
             ^cudnn$cudnnFilterStruct filter-desc (:filter-desc descriptor)
             backward-filter-algorithm (get-in algorithms [:backward-filter :algorithm])]
         (cuda-base/cudnn-call (cudnn/cudnnConvolutionBackwardFilter
                                cudnn-context
                                (value->ptr 1 datatype)
                                input-tensor
                                (->ptr input)
                                output-tensor
                                (->ptr output-gradient)
                                conv-desc
                                backward-filter-algorithm
                                (->ptr workspace)
                                (long workspace-ecount)
                                (value->ptr weight-gradient-alpha datatype)
                                filter-desc
                                (->ptr weight-gradient)))))))

  (convolution-backward-data! [stream
                               input-gradient input-gradient-dims input-gradient-alpha
                               output-gradient output-gradient-dims
                               weights weights-dims
                               workspace workspace-ecount
                               conv-descriptor algorithms]
    (resource/with-resource-context
      (cuda-base/cudnn-with-stream
       stream
       (let [{:keys [datatype descriptor]} conv-descriptor
             [batch-size in-channels in-height in-width] (get input-gradient-dims :shape)
             [batch-size out-channels out-height out-width] (get output-gradient-dims :shape)
             input-tensor (cuda-base/tensor datatype batch-size in-channels in-width in-height)
             output-tensor (cuda-base/tensor datatype batch-size out-channels out-width out-height)
             ^cudnn$cudnnConvolutionStruct conv-desc (:conv-desc descriptor)
             ^cudnn$cudnnFilterStruct filter-desc (:filter-desc descriptor)
             backward-data-algorithm (get-in algorithms [:backward-data :algorithm])]
         (cuda-base/cudnn-call
          (cudnn/cudnnConvolutionBackwardData
           cudnn-context
           (value->ptr 1 datatype)
           filter-desc
           (->ptr weights)
           output-tensor
           (->ptr output-gradient)
           conv-desc
           backward-data-algorithm
           (->ptr workspace)
           workspace-ecount
           (value->ptr input-gradient-alpha datatype)
           input-tensor
           (->ptr input-gradient))))))))
