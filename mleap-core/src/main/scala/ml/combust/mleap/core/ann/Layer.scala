package ml.combust.mleap.core.ann

/** NOTE: this code was taken from Spark and adopted for
  * use by MLeap outside of a Spark Context
  *
  * https://github.com/apache/spark/blob/v2.3.0/mllib/src/main/scala/org/apache/spark/ml/ann/Layer.scala
  */

import java.util.Random

import breeze.linalg.{*, DenseMatrix => BDM, DenseVector => BDV}
import org.apache.spark.ml.linalg.{Vector, Vectors, mleap}

/**
  * Trait that holds Layer properties, that are needed to instantiate it.
  * Implements Layer instantiation.
  *
  */
trait Layer extends Serializable {

  /**
    * Number of weights that is used to allocate memory for the weights vector
    */
  val weightSize: Int

  /**
    * Returns the output size given the input size (not counting the stack size).
    * Output size is used to allocate memory for the output.
    *
    * @param inputSize input size
    * @return output size
    */
  def getOutputSize(inputSize: Int): Int

  /**
    * If true, the memory is not allocated for the output of this layer.
    * The memory allocated to the previous layer is used to write the output of this layer.
    * Developer can set this to true if computing delta of a previous layer
    * does not involve its output, so the current layer can write there.
    * This also mean that both layers have the same number of outputs.
    */
  val inPlace: Boolean

  /**
    * Returns the instance of the layer based on weights provided.
    * Size of weights must be equal to weightSize
    *
    * @param initialWeights vector with layer weights
    * @return the layer model
    */
  def createModel(initialWeights: BDV[Double]): LayerModel

  /**
    * Returns the instance of the layer with random generated weights.
    *
    * @param weights vector for weights initialization, must be equal to weightSize
    * @param random random number generator
    * @return the layer model
    */
  def initModel(weights: BDV[Double], random: Random): LayerModel
}

/**
  * Trait that holds Layer weights (or parameters).
  * Implements functions needed for forward propagation, computing delta and gradient.
  * Can return weights in Vector format.
  */
trait LayerModel extends Serializable {

  val weights: BDV[Double]
  /**
    * Evaluates the data (process the data through the layer).
    * Output is allocated based on the size provided by the
    * LayerModel implementation and the stack (batch) size.
    * Developer is responsible for checking the size of output
    * when writing to it.
    *
    * @param data data
    * @param output output (modified in place)
    */
  def eval(data: BDM[Double], output: BDM[Double]): Unit

  /**
    * Computes the delta for back propagation.
    * Delta is allocated based on the size provided by the
    * LayerModel implementation and the stack (batch) size.
    * Developer is responsible for checking the size of
    * prevDelta when writing to it.
    *
    * @param delta delta of this layer
    * @param output output of this layer
    * @param prevDelta the previous delta (modified in place)
    */
  def computePrevDelta(delta: BDM[Double], output: BDM[Double], prevDelta: BDM[Double]): Unit

  /**
    * Computes the gradient.
    * cumGrad is a wrapper on the part of the weight vector.
    * Size of cumGrad is based on weightSize provided by
    * implementation of LayerModel.
    *
    * @param delta delta for this layer
    * @param input input data
    * @param cumGrad cumulative gradient (modified in place)
    */
  def grad(delta: BDM[Double], input: BDM[Double], cumGrad: BDV[Double]): Unit
}

/**
  * Layer properties of affine transformations, that is y=A*x+b
  *
  * @param numIn number of inputs
  * @param numOut number of outputs
  */
class AffineLayer(val numIn: Int, val numOut: Int) extends Layer {

  override val weightSize = numIn * numOut + numOut

  override def getOutputSize(inputSize: Int): Int = numOut

  override val inPlace = false

  override def createModel(weights: BDV[Double]): LayerModel = new AffineLayerModel(weights, this)

  override def initModel(weights: BDV[Double], random: Random): LayerModel =
    AffineLayerModel(this, weights, random)
}

/**
  * Model of Affine layer
  *
  * @param weights weights
  * @param layer layer properties
  */
class AffineLayerModel (val weights: BDV[Double],
                        val layer: AffineLayer) extends LayerModel {
  val w = new BDM[Double](layer.numOut, layer.numIn, weights.data, weights.offset)
  val b =
    new BDV[Double](weights.data, weights.offset + (layer.numOut * layer.numIn), 1, layer.numOut)

  private var ones: BDV[Double] = null

  override def eval(data: BDM[Double], output: BDM[Double]): Unit = {
    output(::, *) := b
    BreezeUtil.dgemm(1.0, w, data, 1.0, output)
  }

  override def computePrevDelta(
                                 delta: BDM[Double],
                                 output: BDM[Double],
                                 prevDelta: BDM[Double]): Unit = {
    BreezeUtil.dgemm(1.0, w.t, delta, 0.0, prevDelta)
  }

  override def grad(delta: BDM[Double], input: BDM[Double], cumGrad: BDV[Double]): Unit = {
    // compute gradient of weights
    val cumGradientOfWeights = new BDM[Double](w.rows, w.cols, cumGrad.data, cumGrad.offset)
    BreezeUtil.dgemm(1.0 / input.cols, delta, input.t, 1.0, cumGradientOfWeights)
    if (ones == null || ones.length != delta.cols) ones = BDV.ones[Double](delta.cols)
    // compute gradient of bias
    val cumGradientOfBias = new BDV[Double](cumGrad.data, cumGrad.offset + w.size, 1, b.length)
    BreezeUtil.dgemv(1.0 / input.cols, delta, ones, 1.0, cumGradientOfBias)
  }
}

/**
  * Fabric for Affine layer models
  */
object AffineLayerModel {

  /**
    * Creates a model of Affine layer
    *
    * @param layer layer properties
    * @param weights vector for weights initialization
    * @param random random number generator
    * @return model of Affine layer
    */
  def apply(layer: AffineLayer, weights: BDV[Double], random: Random): AffineLayerModel = {
    randomWeights(layer.numIn, layer.numOut, weights, random)
    new AffineLayerModel(weights, layer)
  }

  /**
    * Initialize weights randomly in the interval.
    * Uses [Bottou-88] heuristic [-a/sqrt(in); a/sqrt(in)],
    * where `a` is chosen in such a way that the weight variance corresponds
    * to the points to the maximal curvature of the activation function
    * (which is approximately 2.38 for a standard sigmoid).
    *
    * @param numIn number of inputs
    * @param numOut number of outputs
    * @param weights vector for weights initialization
    * @param random random number generator
    */
  def randomWeights(
                     numIn: Int,
                     numOut: Int,
                     weights: BDV[Double],
                     random: Random): Unit = {
    var i = 0
    val sqrtIn = math.sqrt(numIn)
    while (i < weights.length) {
      weights(i) = (random.nextDouble * 4.8 - 2.4) / sqrtIn
      i += 1
    }
  }
}

/**
  * Trait for functions and their derivatives for functional layers
  */
trait ActivationFunction extends Serializable {

  /**
    * Implements a function
    */
  def eval: Double => Double

  /**
    * Implements a derivative of a function (needed for the back propagation)
    */
  def derivative: Double => Double
}

/**
  * Implements in-place application of functions in the arrays
  */
object ApplyInPlace {

  // TODO: use Breeze UFunc
  def apply(x: BDM[Double], y: BDM[Double], func: Double => Double): Unit = {
    var i = 0
    while (i < x.rows) {
      var j = 0
      while (j < x.cols) {
        y(i, j) = func(x(i, j))
        j += 1
      }
      i += 1
    }
  }

  // TODO: use Breeze UFunc
  def apply(
             x1: BDM[Double],
             x2: BDM[Double],
             y: BDM[Double],
             func: (Double, Double) => Double): Unit = {
    var i = 0
    while (i < x1.rows) {
      var j = 0
      while (j < x1.cols) {
        y(i, j) = func(x1(i, j), x2(i, j))
        j += 1
      }
      i += 1
    }
  }
}

/**
  * Implements Sigmoid activation function
  */
class SigmoidFunction extends ActivationFunction {
  override def eval: (Double) => Double = x => 1.0 / (1 + math.exp(-x))
  override def derivative: (Double) => Double = z => (1 - z) * z
}

/**
  * Functional layer properties, y = f(x)
  *
  * @param activationFunction activation function
  */
class FunctionalLayer (val activationFunction: ActivationFunction) extends Layer {

  override val weightSize = 0

  override def getOutputSize(inputSize: Int): Int = inputSize

  override val inPlace = true

  override def createModel(weights: BDV[Double]): LayerModel = new FunctionalLayerModel(this)

  override def initModel(weights: BDV[Double], random: Random): LayerModel =
    createModel(weights)
}

/**
  * Functional layer model. Holds no weights.
  *
  * @param layer functional layer
  */
class FunctionalLayerModel (val layer: FunctionalLayer)
  extends LayerModel {

  // empty weights
  val weights = new BDV[Double](0)

  override def eval(data: BDM[Double], output: BDM[Double]): Unit = {
    ApplyInPlace(data, output, layer.activationFunction.eval)
  }

  override def computePrevDelta(
                                 nextDelta: BDM[Double],
                                 input: BDM[Double],
                                 delta: BDM[Double]): Unit = {
    ApplyInPlace(input, delta, layer.activationFunction.derivative)
    delta :*= nextDelta
  }

  override def grad(delta: BDM[Double], input: BDM[Double], cumGrad: BDV[Double]): Unit = {}
}

/**
  * Trait for the artificial neural network (ANN) topology properties
  */
trait Topology extends Serializable {
  def model(weights: Vector): TopologyModel
  def model(seed: Long): TopologyModel
}

/**
  * Trait for ANN topology model
  */
trait TopologyModel extends Serializable {

  val weights: Vector
  /**
    * Array of layers
    */
  val layers: Array[Layer]

  /**
    * Array of layer models
    */
  val layerModels: Array[LayerModel]

  /**
    * Forward propagation
    *
    * @param data input data
    * @param includeLastLayer Include the last layer in the output. In
    *                         MultilayerPerceptronClassifier, the last layer is always softmax;
    *                         the last layer of outputs is needed for class predictions, but not
    *                         for rawPrediction.
    *
    * @return array of outputs for each of the layers
    */
  def forward(data: BDM[Double], includeLastLayer: Boolean): Array[BDM[Double]]

  /**
    * Prediction of the model. See {@link ProbabilisticClassificationModel}
    *
    * @param features input features
    * @return prediction
    */
  def predict(features: Vector): Vector

  /**
    * Raw prediction of the model. See {@link ProbabilisticClassificationModel}
    *
    * @param features input features
    * @return raw prediction
    *
    * Note: This interface is only used for classification Model.
    */
  def predictRaw(features: Vector): Vector

  /**
    * Probability of the model. See {@link ProbabilisticClassificationModel}
    *
    * @param rawPrediction raw prediction vector
    * @return probability
    *
    * Note: This interface is only used for classification Model.
    */
  def raw2ProbabilityInPlace(rawPrediction: Vector): Vector

  /**
    * Computes gradient for the network
    *
    * @param data input data
    * @param target target output
    * @param cumGradient cumulative gradient
    * @param blockSize block size
    * @return error
    */
  def computeGradient(data: BDM[Double], target: BDM[Double], cumGradient: Vector,
                      blockSize: Int): Double
}

/**
  * Feed forward ANN
  *
  * @param layers Array of layers
  */
class FeedForwardTopology private(val layers: Array[Layer]) extends Topology {
  override def model(weights: Vector): TopologyModel = FeedForwardModel(this, weights)

  override def model(seed: Long): TopologyModel = FeedForwardModel(this, seed)
}

/**
  * Factory for some of the frequently-used topologies
  */
object FeedForwardTopology {
  /**
    * Creates a feed forward topology from the array of layers
    *
    * @param layers array of layers
    * @return feed forward topology
    */
  def apply(layers: Array[Layer]): FeedForwardTopology = {
    new FeedForwardTopology(layers)
  }

  /**
    * Creates a multi-layer perceptron
    *
    * @param layerSizes sizes of layers including input and output size
    * @param softmaxOnTop whether to use SoftMax or Sigmoid function for an output layer.
    *                Softmax is default
    * @return multilayer perceptron topology
    */
  def multiLayerPerceptron(layerSizes: Array[Int],
                            softmaxOnTop: Boolean = true): FeedForwardTopology = {
    val layers = new Array[Layer]((layerSizes.length - 1) * 2)
    for (i <- 0 until layerSizes.length - 1) {
      layers(i * 2) = new AffineLayer(layerSizes(i), layerSizes(i + 1))
      layers(i * 2 + 1) =
        if (i == layerSizes.length - 2) {
          if (softmaxOnTop) {
            new SoftmaxLayerWithCrossEntropyLoss()
          } else {
            // TODO: squared error is more natural but converges slower
            new SigmoidLayerWithSquaredError()
          }
        } else {
          new FunctionalLayer(new SigmoidFunction())
        }
    }
    FeedForwardTopology(layers)
  }
}

/**
  * Model of Feed Forward Neural Network.
  * Implements forward, gradient computation and can return weights in vector format.
  *
  * @param weights network weights
  * @param topology network topology
  */
class FeedForwardModel private(
                                val weights: Vector,
                                val topology: FeedForwardTopology) extends TopologyModel {

  val layers = topology.layers
  val layerModels = new Array[LayerModel](layers.length)
  private var offset = 0
  for (i <- 0 until layers.length) {
    layerModels(i) = layers(i).createModel(
      new BDV[Double](weights.toArray, offset, 1, layers(i).weightSize))
    offset += layers(i).weightSize
  }

  override def forward(data: BDM[Double], includeLastLayer: Boolean): Array[BDM[Double]] = {
    var outputs: Array[BDM[Double]] = null
    // Initialize output arrays for all layers. Special treatment for InPlace
    val currentBatchSize = data.cols
    // TODO: allocate outputs as one big array and then create BDMs from it
    if (outputs == null || outputs(0).cols != currentBatchSize) {
      outputs = new Array[BDM[Double]](layers.length)
      var inputSize = data.rows
      for (i <- 0 until layers.length) {
        if (layers(i).inPlace) {
          outputs(i) = outputs(i - 1)
        } else {
          val outputSize = layers(i).getOutputSize(inputSize)
          outputs(i) = new BDM[Double](outputSize, currentBatchSize)
          inputSize = outputSize
        }
      }
    }
    layerModels(0).eval(data, outputs(0))
    val end = if (includeLastLayer) layerModels.length else layerModels.length - 1
    for (i <- 1 until end) {
      layerModels(i).eval(outputs(i - 1), outputs(i))
    }
    outputs
  }

  override def computeGradient(
                                data: BDM[Double],
                                target: BDM[Double],
                                cumGradient: Vector,
                                realBatchSize: Int): Double = {
    var deltas: Array[BDM[Double]] = null
    val outputs = forward(data, true)
    val currentBatchSize = data.cols
    // TODO: allocate deltas as one big array and then create BDMs from it
    if (deltas == null || deltas(0).cols != currentBatchSize) {
      deltas = new Array[BDM[Double]](layerModels.length)
      var inputSize = data.rows
      for (i <- 0 until layerModels.length - 1) {
        val outputSize = layers(i).getOutputSize(inputSize)
        deltas(i) = new BDM[Double](outputSize, currentBatchSize)
        inputSize = outputSize
      }
    }
    val L = layerModels.length - 1
    // TODO: explain why delta of top layer is null (because it might contain loss+layer)
    val loss = layerModels.last match {
      case levelWithError: LossFunction => levelWithError.loss(outputs.last, target, deltas(L - 1))
      case _ =>
        throw new UnsupportedOperationException("Top layer is required to have objective.")
    }
    for (i <- (L - 2) to (0, -1)) {
      layerModels(i + 1).computePrevDelta(deltas(i + 1), outputs(i + 1), deltas(i))
    }
    val cumGradientArray = cumGradient.toArray
    var offset = 0
    for (i <- 0 until layerModels.length) {
      val input = if (i == 0) data else outputs(i - 1)
      layerModels(i).grad(deltas(i), input,
        new BDV[Double](cumGradientArray, offset, 1, layers(i).weightSize))
      offset += layers(i).weightSize
    }
    loss
  }

  override def predict(data: Vector): Vector = {
    val size = data.size
    val result = forward(new BDM[Double](size, 1, data.toArray), true)
    Vectors.dense(result.last.toArray)
  }

  override def predictRaw(data: Vector): Vector = {
    val result = forward(new BDM[Double](data.size, 1, data.toArray), false)
    Vectors.dense(result(result.length - 2).toArray)
  }

  override def raw2ProbabilityInPlace(data: Vector): Vector = {
    val dataMatrix = new BDM[Double](data.size, 1, data.toArray)
    layerModels.last.eval(dataMatrix, dataMatrix)
    data
  }
}

/**
  * Fabric for feed forward ANN models
  */
object FeedForwardModel {

  /**
    * Creates a model from a topology and weights
    *
    * @param topology topology
    * @param weights weights
    * @return model
    */
  def apply(topology: FeedForwardTopology, weights: Vector): FeedForwardModel = {
    val expectedWeightSize = topology.layers.map(_.weightSize).sum
    require(weights.size == expectedWeightSize,
      s"Expected weight vector of size $expectedWeightSize but got size ${weights.size}.")
    new FeedForwardModel(weights, topology)
  }

  /**
    * Creates a model given a topology and seed
    *
    * @param topology topology
    * @param seed seed for generating the weights
    * @return model
    */
  def apply(topology: FeedForwardTopology, seed: Long = 11L): FeedForwardModel = {
    val layers = topology.layers
    val layerModels = new Array[LayerModel](layers.length)
    val weights = BDV.zeros[Double](topology.layers.map(_.weightSize).sum)
    var offset = 0
    val random = new Random(seed)
    for (i <- layers.indices) {
      layerModels(i) = layers(i).
        initModel(new BDV[Double](weights.data, offset, 1, layers(i).weightSize), random)
      offset += layers(i).weightSize
    }
    new FeedForwardModel(mleap.VectorUtil.fromBreeze(weights), topology)
  }
}